package com.academy.paybridge.settlement.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.ledger.api.LedgerApi;
import com.academy.paybridge.ledger.api.LedgerPositionsApi;
import com.academy.paybridge.settlement.api.SweepReport;
import com.academy.paybridge.settlement.api.SweepReport.Failure;
import com.academy.paybridge.settlement.api.SweepView;
import com.academy.paybridge.settlement.service.SweepRules.Plan;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

/**
 * R-SET-11: moves the balance of a fee or tax account to its destination in one balanced journal.
 * Each source account is its own transaction. A system wallet is debited through AccountApi in the same
 * transaction as its journal, so the wallet and the ledger can never disagree (concern H).
 */
@Service
public class SweepService {

    private static final Logger log = LoggerFactory.getLogger(SweepService.class);

    private final LedgerPositionsApi positions;
    private final LedgerApi ledger;
    private final AccountApi accounts;
    private final AuditService audit;
    private final CurrencyRegistry currencies;
    private final TransactionTemplate tx;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SweepService(LedgerPositionsApi positions, LedgerApi ledger, AccountApi accounts, AuditService audit,
                        CurrencyRegistry currencies, PlatformTransactionManager txManager, JdbcTemplate jdbc,
                        Clock clock) {
        this.positions = positions;
        this.ledger = ledger;
        this.accounts = accounts;
        this.audit = audit;
        this.currencies = currencies;
        this.tx = new TransactionTemplate(txManager);
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** @param onlyCurrency a three-letter code to sweep one currency, or null for all */
    public SweepReport run(String actor, String onlyCurrency) {
        List<SweepView> swept = new ArrayList<>();
        List<String> nothing = new ArrayList<>();
        List<Failure> failed = new ArrayList<>();
        for (Plan plan : SweepRules.plans(positions.positions(), onlyCurrency)) {
            try {
                SweepView view = tx.execute(status -> sweepOne(plan, actor));
                if (view == null) {
                    nothing.add(plan.source());
                } else {
                    swept.add(view);
                }
            } catch (RuntimeException e) {
                log.warn("Sweep of {} failed", plan.source(), e);
                String reason = e instanceof ApiException ? e.getMessage() : "Could not sweep this account. See the log.";
                failed.add(new Failure(plan.source(), reason));
            }
        }
        return new SweepReport(clock.instant(), swept, nothing, failed);
    }

    /** Runs inside a transaction. Returns null when there is nothing to sweep. */
    private SweepView sweepOne(Plan plan, String actor) {
        // One sweep of one account at a time: the second caller waits here, then reads the new (smaller) position.
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(?))", "sweep:" + plan.source() + ":" + plan.currency());
        long amount = SweepRules.amountOf(positions.positions(), plan);
        if (amount <= 0) {
            return null;
        }
        long id = jdbc.queryForObject("SELECT nextval('sweeps_id_seq')", Long.class);
        String key = "SWEEP:" + id;
        jdbc.update("INSERT INTO sweeps (id, source_account, currency, amount_minor, destination_account, "
                        + "journal_key, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, plan.source(), plan.currency(), amount, plan.destination(), key, actor);
        if (plan.walletAccount() != null) {
            accounts.debit(plan.walletAccount(), plan.currency(), amount);
        }
        ledger.recordJournal(key, key, "SWEEP", SweepRules.lines(plan, amount));
        audit.record(actor, "SETTLEMENT_SWEEP", key,
                plan.source() + " to " + plan.destination() + ": " + amount + " " + plan.currency());
        return new SweepView(id, plan.source(), plan.destination(), plan.currency(), amount,
                currencies.format(amount, plan.currency()), key, actor);
    }
}