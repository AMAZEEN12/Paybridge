package com.academy.paybridge.settlement.service;

import com.academy.paybridge.fx.api.ConversionDayApi;
import com.academy.paybridge.remittance.api.PayoutDayApi;
import com.academy.paybridge.settlement.api.BatchView;
import com.academy.paybridge.settlement.api.RunSummary;
import com.academy.paybridge.settlement.domain.PartnerStatementLine;
import com.academy.paybridge.settlement.domain.SettlementBatch;
import com.academy.paybridge.settlement.domain.SettlementBatchLine;
import com.academy.paybridge.settlement.repository.PartnerStatementLineRepository;
import com.academy.paybridge.settlement.repository.SettlementBatchLineRepository;
import com.academy.paybridge.settlement.repository.SettlementBatchRepository;
import com.academy.paybridge.shared.api.DayItem;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.transfer.api.TransferDayApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.academy.paybridge.shared.money.CurrencyInfo;
import com.academy.paybridge.shared.money.CurrencyRegistry;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * Plan 002-004, step C2 (R-SET-3 to 7). For each partner and currency, one batch per UTC day.
 * <p>
 * FLUTTERWAVE batches hold the payouts and are checked against the partner statement. PAYBRIDGE batches hold our own
 * internal transfers and conversions; they have no partner, so the partner total is our total.
 * <p>
 * The order is always: read our items, fetch the statement (no transaction open, R-SET-7), then one short transaction
 * that writes the batch, its lines and the statement lines together. The unique key makes a second run change nothing.
 */
@Service
public class SettlementBatchService {

    private static final Logger log = LoggerFactory.getLogger(SettlementBatchService.class);
    static final String INTERNAL_PARTNER = "PAYBRIDGE";
    private static final int MAX_CATCH_UP_DAYS = 31;

    private final PayoutDayApi payouts;
    private final TransferDayApi transfers;
    private final ConversionDayApi conversions;
    private final StatementProvider statements;
    private final SettlementBatchRepository batches;
    private final SettlementBatchLineRepository lines;
    private final PartnerStatementLineRepository statementLines;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final CurrencyRegistry currencies;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public SettlementBatchService(PayoutDayApi payouts, TransferDayApi transfers, ConversionDayApi conversions,
                                  StatementProvider statements, SettlementBatchRepository batches,
                                  SettlementBatchLineRepository lines, PartnerStatementLineRepository statementLines,
                                  AuditService audit, PlatformTransactionManager txManager, JdbcTemplate jdbc,
                                  Clock clock, CurrencyRegistry currencies) {
        this.payouts = payouts;
        this.transfers = transfers;
        this.conversions = conversions;
        this.statements = statements;
        this.batches = batches;
        this.lines = lines;
        this.statementLines = statementLines;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.jdbc = jdbc;
        this.clock = clock;
        this.currencies = currencies;
    }

    /** The UTC day as a half-open window. A transfer at 00:30 Lagos (23:30 UTC) belongs to the previous day. */
    public static Instant[] window(LocalDate utcDate) {
        return new Instant[]{utcDate.atStartOfDay(ZoneOffset.UTC).toInstant(),
                utcDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant()};
    }

    /** The scheduled run: every UTC day since the last finished one, up to yesterday. Single-flight. */
    public List<RunSummary> catchUp() {
        if (!running.compareAndSet(false, true)) {
            return List.of();
        }
        try {
            LocalDate yesterday = LocalDate.now(clock.withZone(ZoneOffset.UTC)).minusDays(1);
            LocalDate last = jdbc.query("SELECT max(period_date) FROM settlement_runs",
                    rs -> rs.next() && rs.getDate(1) != null ? rs.getDate(1).toLocalDate() : null);
            LocalDate start = last == null ? yesterday : last.plusDays(1);
            LocalDate earliest = yesterday.minusDays(MAX_CATCH_UP_DAYS - 1L);
            if (start.isBefore(earliest)) {
                start = earliest;
            }
            List<RunSummary> done = new ArrayList<>();
            for (LocalDate d = start; !d.isAfter(yesterday); d = d.plusDays(1)) {
                done.add(runDay(d));
            }
            return done;
        } finally {
            running.set(false);
        }
    }

    /** A manual run (O14): only for a finished UTC day that has no batch and has not been run. Single-flight. */
    public RunSummary runManually(LocalDate date) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        if (!date.isBefore(today)) {
            throw ApiException.badRequest("DAY_NOT_FINISHED", "Only a finished UTC day can be run.");
        }
        if (!running.compareAndSet(false, true)) {
            throw ApiException.conflict("RUN_IN_PROGRESS", "A settlement run is already in progress.");
        }
        try {
            if (batches.existsByPeriodDate(date) || hasRun(date)) {
                throw ApiException.conflict("DAY_ALREADY_RUN", "That day has already been run and cannot be run again.");
            }
            return runDay(date);
        } finally {
            running.set(false);
        }
    }

    public List<BatchView> list(LocalDate date) {
        return batches.findByPeriodDateOrderByPartnerAscCurrencyAsc(date).stream().map(SettlementBatchService::view).toList();
    }

    /** Builds every missing batch for one UTC day. Safe to call twice: existing batches are left alone (R-SET-4). */
    public RunSummary runDay(LocalDate date) {
        Instant[] w = window(date);
        // 1. Read our side. Each call is its own short read; nothing stays open afterwards.
        List<DayItem> payoutItems = payouts.payoutsBetween(w[0], w[1]);
        List<DayItem> internalItems = new ArrayList<>(transfers.internalTransfersBetween(w[0], w[1]));
        internalItems.addAll(conversions.conversionsBetween(w[0], w[1]));

        int created = 0;
        int existing = 0;
        Map<String, List<DayItem>> payoutsByCurrency = new TreeMap<>(byCurrency(payoutItems));
        // A partner line in a currency we had nothing in still gets a (empty) batch, so matching can flag it (R-SET-8).
        for (CurrencyInfo c : currencies.usable()) {
            if (!payoutsByCurrency.containsKey(c.code())
                    && !batches.existsByPartnerAndCurrencyAndPeriodDate(statements.partner(), c.code(), date)
                    && !statements.list(c.code(), w[0], w[1], 0).lines().isEmpty()) {
                payoutsByCurrency.put(c.code(), List.of());
            }
        }
        for (Map.Entry<String, List<DayItem>> e : payoutsByCurrency.entrySet()) {
            if (ensureBatch(statements.partner(), e.getKey(), date, w, e.getValue())) created++; else existing++;
        }
        for (Map.Entry<String, List<DayItem>> e : byCurrency(internalItems).entrySet()) {
            if (ensureBatch(INTERNAL_PARTNER, e.getKey(), date, w, e.getValue())) created++; else existing++;
        }
        jdbc.update("INSERT INTO settlement_runs (period_date) VALUES (?) ON CONFLICT DO NOTHING", java.sql.Date.valueOf(date));
        log.info("Settlement run for {}: {} batch(es) created, {} already there", date, created, existing);
        return new RunSummary(date, created, existing);
    }

    private boolean ensureBatch(String partner, String currency, LocalDate date, Instant[] w, List<DayItem> items) {
        if (batches.existsByPartnerAndCurrencyAndPeriodDate(partner, currency, date)) {
            return false;
        }
        // 2. Fetch the statement with no transaction open (R-SET-7).
        List<StatementProvider.Line> statement = INTERNAL_PARTNER.equals(partner) ? List.of() : fetchAll(currency, w);
        long ourTotal = items.stream().filter(i -> !"FAILED".equals(i.status())).mapToLong(DayItem::amountMinor).sum();
        long partnerTotal = INTERNAL_PARTNER.equals(partner) ? ourTotal
                : statement.stream().filter(l -> !"FAILED".equals(l.status())).mapToLong(StatementProvider.Line::amountMinor).sum();
        // 3. One short transaction: batch, lines and statement lines together.
        try {
            return Boolean.TRUE.equals(tx.execute(status -> {
                if (batches.existsByPartnerAndCurrencyAndPeriodDate(partner, currency, date)) {
                    return false;
                }
                Instant now = clock.instant();
                SettlementBatch batch = batches.saveAndFlush(SettlementBatch.closed(partner, currency, date, w[0], w[1],
                        items.size(), ourTotal, partnerTotal, now));
                lines.saveAll(items.stream().map(i -> new SettlementBatchLine(batch.getId(), i.reference(), i.kind(),
                        i.amountMinor(), i.status())).toList());
                statementLines.saveAll(statement.stream().map(l -> new PartnerStatementLine(partner, currency,
                        l.reference(), l.amountMinor(), l.status(), l.partnerTime(), now, batch.getId())).toList());
                audit.record("system", "SETTLEMENT_BATCH_CLOSED", partner + ":" + currency + ":" + date,
                        items.size() + " item(s), our total " + ourTotal + ", partner total " + partnerTotal);
                return true;
            }));
        } catch (DataIntegrityViolationException raced) {
            return false; // another instance wrote the same batch first; the unique key kept it to one
        }
    }

    private List<StatementProvider.Line> fetchAll(String currency, Instant[] w) {
        List<StatementProvider.Line> all = new ArrayList<>();
        for (int page = 0; page < 1000; page++) {
            StatementProvider.Page p = statements.list(currency, w[0], w[1], page);
            all.addAll(p.lines());
            if (!p.hasMore()) {
                return all;
            }
        }
        throw new IllegalStateException("Statement for " + currency + " has too many pages");
    }

    private static Map<String, List<DayItem>> byCurrency(List<DayItem> items) {
        return items.stream().collect(Collectors.groupingBy(DayItem::currency, TreeMap::new, Collectors.toList()));
    }

    private boolean hasRun(LocalDate date) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM settlement_runs WHERE period_date = ?", Integer.class,
                java.sql.Date.valueOf(date));
        return n != null && n > 0;
    }

    private static BatchView view(SettlementBatch b) {
        return new BatchView(b.getId(), b.getPartner(), b.getCurrency(), b.getPeriodDate(), b.getPeriodStart(),
                b.getPeriodEnd(), b.getStatus().name(), b.getItemCount(), b.getOurTotalMinor(),
                b.getPartnerTotalMinor(), b.getClosedAt());
    }
}