package com.academy.paybridge.settlement.service;

import com.academy.paybridge.settlement.api.ExceptionView;
import com.academy.paybridge.settlement.domain.BatchStatus;
import com.academy.paybridge.settlement.domain.PartnerStatementLine;
import com.academy.paybridge.settlement.domain.SettlementBatch;
import com.academy.paybridge.settlement.domain.SettlementBatchLine;
import com.academy.paybridge.settlement.repository.PartnerStatementLineRepository;
import com.academy.paybridge.settlement.repository.SettlementBatchLineRepository;
import com.academy.paybridge.settlement.repository.SettlementBatchRepository;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Plan 002-004, step C3 (R-SET-8 to 10). Matches each closed batch against the partner statement and keeps the exceptions.
 * <p>
 * Two passes per partner batch. The first uses the statement stored when the batch closed (C2); a payout the partner
 * has not reported yet waits as AWAITING_PARTNER. The second runs once the statement lag has passed: it fetches the
 * statement again (no transaction open, R-SET-7), stores any new lines, and decides everything that is left. A batch
 * is SETTLED only after that second pass with no open exception. Internal PAYBRIDGE batches have no partner, so they
 * are settled straight away.
 * <p>
 * A payout that is PENDING or RETRYING is only ever reported. Nothing here moves money.
 */
@Service
public class SettlementMatchingService {

    private static final Logger log = LoggerFactory.getLogger(SettlementMatchingService.class);
    static final String AWAITING = MatchRules.Result.AWAITING_PARTNER.name();

    private static final String VIEW_SQL =
            "SELECT e.id, e.batch_id, b.partner, b.currency, b.period_date, e.reference, e.type, e.our_amount_minor, "
                    + "e.partner_amount_minor, e.our_status, e.partner_status, e.created_at, e.resolved_at, "
                    + "e.resolved_by, e.resolution_note "
                    + "FROM settlement_exceptions e JOIN settlement_batches b ON b.id = e.batch_id ";

    private static final RowMapper<ExceptionView> VIEW = (rs, n) -> new ExceptionView(
            rs.getLong("id"), rs.getLong("batch_id"), rs.getString("partner"), rs.getString("currency"),
            rs.getDate("period_date").toLocalDate(), rs.getString("reference"), rs.getString("type"),
            rs.getObject("our_amount_minor", Long.class), rs.getObject("partner_amount_minor", Long.class),
            rs.getString("our_status"), rs.getString("partner_status"), instant(rs.getTimestamp("created_at")),
            instant(rs.getTimestamp("resolved_at")), rs.getString("resolved_by"), rs.getString("resolution_note"));

    private final StatementProvider statements;
    private final SettlementBatchRepository batches;
    private final SettlementBatchLineRepository lines;
    private final PartnerStatementLineRepository statementLines;
    private final SettlementProperties props;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SettlementMatchingService(StatementProvider statements, SettlementBatchRepository batches,
                                     SettlementBatchLineRepository lines, PartnerStatementLineRepository statementLines,
                                     SettlementProperties props, AuditService audit,
                                     PlatformTransactionManager txManager, JdbcTemplate jdbc, Clock clock) {
        this.statements = statements;
        this.batches = batches;
        this.lines = lines;
        this.statementLines = statementLines;
        this.props = props;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Matches every batch that has a pass due. Safe to call often and from two instances. Returns how many it looked at. */
    public int matchDue() {
        Timestamp lagCutoff = Timestamp.from(clock.instant().minus(props.statementLag()));
        List<Long> due = jdbc.queryForList(
                "SELECT id FROM settlement_batches WHERE matched_at IS NULL "
                        + "OR (final_check_at IS NULL AND period_end <= ?) ORDER BY id", Long.class, lagCutoff);
        for (Long id : due) {
            matchBatch(id);
        }
        return due.size();
    }

    public void matchBatch(long batchId) {
        SettlementBatch batch = batches.findById(batchId)
                .orElseThrow(() -> ApiException.notFound("BATCH_NOT_FOUND", "No such batch."));
        if (jdbc.queryForObject("SELECT final_check_at IS NOT NULL FROM settlement_batches WHERE id = ?",
                Boolean.class, batchId)) {
            return;
        }
        Instant now = clock.instant();
        boolean internal = SettlementBatchService.INTERNAL_PARTNER.equals(batch.getPartner());
        boolean lagPassed = !now.isBefore(batch.getPeriodEnd().plus(props.statementLag()));
        // R-SET-7: the second pass fetches with no transaction open, then decides in one short one.
        List<StatementProvider.Line> fresh = !internal && lagPassed ? fetchAll(batch) : null;
        tx.executeWithoutResult(status -> decide(batch, internal, lagPassed, fresh, now));
    }

    private void decide(SettlementBatch batch, boolean internal, boolean lagPassed,
                        List<StatementProvider.Line> fresh, Instant now) {
        long id = batch.getId();
        // The batch row is the lock: two instances matching the same batch take turns and the second finds nothing to do.
        Map<String, Object> flags = jdbc.queryForMap(
                "SELECT matched_at, final_check_at FROM settlement_batches WHERE id = ? FOR UPDATE", id);
        if (flags.get("final_check_at") != null) {
            return;
        }
        boolean finalPass = internal || lagPassed;
        if (flags.get("matched_at") != null && !finalPass) {
            return;
        }
        Timestamp ts = Timestamp.from(now);
        int opened = 0;

        if (internal) {
            jdbc.update("UPDATE settlement_batch_lines SET match_result = 'MATCHED' WHERE batch_id = ? AND match_result IS NULL", id);
        } else {
            if (fresh != null) {
                for (StatementProvider.Line l : fresh) {
                    jdbc.update("INSERT INTO partner_statement_lines (partner, currency, reference, amount_minor, status, "
                                    + "partner_time, fetched_at, batch_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                                    + "ON CONFLICT (batch_id, reference) DO NOTHING",
                            batch.getPartner(), batch.getCurrency(), l.reference(), l.amountMinor(), l.status(),
                            l.partnerTime() == null ? null : Timestamp.from(l.partnerTime()), ts, id);
                }
            }
            Map<String, PartnerStatementLine> theirs = new LinkedHashMap<>();
            for (PartnerStatementLine p : statementLines.findByBatchIdOrderById(id)) {
                theirs.putIfAbsent(p.getReference(), p);
            }
            List<SettlementBatchLine> ours = lines.findByBatchIdOrderById(id);
            for (SettlementBatchLine l : ours) {
                String done = l.getMatchResult();
                if (done != null && !AWAITING.equals(done)) {
                    continue;   // decided on an earlier pass; never decided twice
                }
                PartnerStatementLine p = theirs.get(l.getReference());
                MatchRules.Result r = MatchRules.decide(new MatchRules.Ours(l.getAmountMinor(), l.getOurStatus()),
                        p == null ? null : new MatchRules.Theirs(p.getAmountMinor(), p.getStatus()), lagPassed);
                l.setPartnerStatus(p == null ? null : p.getStatus());
                l.setMatchResult(r.name());
                lines.save(l);
                if (r.isException() && insertException(id, l.getReference(), r, l.getAmountMinor(),
                        p == null ? null : p.getAmountMinor(), l.getOurStatus(), p == null ? null : p.getStatus())) {
                    opened++;
                }
            }
            // R-SET-8: a partner line for a payout we do not have.
            Set<String> ourRefs = new HashSet<>();
            ours.forEach(l -> ourRefs.add(l.getReference()));
            for (PartnerStatementLine p : theirs.values()) {
                if (!ourRefs.contains(p.getReference()) && insertException(id, p.getReference(),
                        MatchRules.Result.MISSING_ON_OUR_SIDE, null, p.getAmountMinor(), null, p.getStatus())) {
                    opened++;
                }
            }
        }

        jdbc.update("UPDATE settlement_batches SET matched_at = COALESCE(matched_at, ?), "
                + "final_check_at = CASE WHEN ? THEN ? ELSE final_check_at END WHERE id = ?", ts, finalPass, ts, id);
        recomputeStatus(id, now);
        audit.record("system", "SETTLEMENT_BATCH_MATCHED",
                batch.getPartner() + ":" + batch.getCurrency() + ":" + batch.getPeriodDate(),
                opened + " exception(s) opened" + (finalPass ? ", final check done" : ", waiting for the statement lag"));
        log.info("Settlement matching for batch {}: {} exception(s) opened (final pass: {})", id, opened, finalPass);
    }

    /** R-SET-10. A note is required; the resolution is stored on the exception and written to audit_log. */
    public ExceptionView resolve(long exceptionId, String actor, String note) {
        if (note == null || note.isBlank()) {
            throw ApiException.badRequest("NOTE_REQUIRED", "A resolution note is required.");
        }
        String clean = note.strip();
        if (clean.length() > 500) {
            throw ApiException.badRequest("NOTE_TOO_LONG", "The note can be at most 500 characters.");
        }
        List<Long> owner = jdbc.queryForList("SELECT batch_id FROM settlement_exceptions WHERE id = ?", Long.class, exceptionId);
        if (owner.isEmpty()) {
            throw ApiException.notFound("EXCEPTION_NOT_FOUND", "No such exception.");
        }
        return tx.execute(status -> {
            Instant now = clock.instant();
            // Same lock order as matching: the batch first, then the exception.
            jdbc.queryForObject("SELECT id FROM settlement_batches WHERE id = ? FOR UPDATE", Long.class, owner.get(0));
            Map<String, Object> row = jdbc.queryForMap(
                    "SELECT type, reference, resolved_at FROM settlement_exceptions WHERE id = ? FOR UPDATE", exceptionId);
            if (row.get("resolved_at") != null) {
                throw ApiException.conflict("ALREADY_RESOLVED", "That exception is already resolved.");
            }
            jdbc.update("UPDATE settlement_exceptions SET resolved_at = ?, resolved_by = ?, resolution_note = ? WHERE id = ?",
                    Timestamp.from(now), actor, clean, exceptionId);
            recomputeStatus(owner.get(0), now);
            audit.record(actor, "SETTLEMENT_EXCEPTION_RESOLVED", "settlement-exception:" + exceptionId,
                    row.get("type") + " " + row.get("reference") + ": " + clean);
            return jdbc.queryForObject(VIEW_SQL + "WHERE e.id = ?", VIEW, exceptionId);
        });
    }

    public List<ExceptionView> list(boolean includeResolved) {
        String where = includeResolved ? "" : "WHERE e.resolved_at IS NULL ";
        return new ArrayList<>(jdbc.query(VIEW_SQL + where + "ORDER BY e.id LIMIT 500", VIEW));
    }

    /** EXCEPTION while any exception is open; SETTLED once the final check is done and none is open; otherwise CLOSED. */
    private void recomputeStatus(long batchId, Instant now) {
        Integer open = jdbc.queryForObject(
                "SELECT count(*) FROM settlement_exceptions WHERE batch_id = ? AND resolved_at IS NULL", Integer.class, batchId);
        Boolean finalDone = jdbc.queryForObject(
                "SELECT final_check_at IS NOT NULL FROM settlement_batches WHERE id = ?", Boolean.class, batchId);
        BatchStatus next = open != null && open > 0 ? BatchStatus.EXCEPTION
                : Boolean.TRUE.equals(finalDone) ? BatchStatus.SETTLED : BatchStatus.CLOSED;
        jdbc.update("UPDATE settlement_batches SET status = ?, "
                        + "settled_at = CASE WHEN ? THEN COALESCE(settled_at, ?) ELSE settled_at END WHERE id = ?",
                next.name(), next == BatchStatus.SETTLED, Timestamp.from(now), batchId);
    }

    private boolean insertException(long batchId, String reference, MatchRules.Result type, Long ourAmount,
                                    Long partnerAmount, String ourStatus, String partnerStatus) {
        return jdbc.update("INSERT INTO settlement_exceptions (batch_id, reference, type, our_amount_minor, "
                        + "partner_amount_minor, our_status, partner_status) VALUES (?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (batch_id, reference) DO NOTHING",
                batchId, reference, type.name(), ourAmount, partnerAmount, ourStatus, partnerStatus) > 0;
    }

    private List<StatementProvider.Line> fetchAll(SettlementBatch batch) {
        List<StatementProvider.Line> all = new ArrayList<>();
        for (int page = 0; page < 1000; page++) {
            StatementProvider.Page p = statements.list(batch.getCurrency(), batch.getPeriodStart(), batch.getPeriodEnd(), page);
            all.addAll(p.lines());
            if (!p.hasMore()) {
                return all;
            }
        }
        throw new IllegalStateException("Statement for " + batch.getCurrency() + " has too many pages");
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}