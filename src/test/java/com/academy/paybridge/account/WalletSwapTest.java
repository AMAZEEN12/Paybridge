package com.academy.paybridge.account;

import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.service.AccountService;
import com.academy.paybridge.account.service.WalletService;
import com.academy.paybridge.ledger.service.ReconciliationService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.support.TestDb;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** No @Transactional on purpose: the lock and concurrency tests need real commits. */
@SpringBootTest
@ActiveProfiles("test")
class WalletSwapTest {

    @Autowired
    AccountService accountService;
    @Autowired
    WalletService walletService;
    @Autowired
    ReconciliationService reconciliation;
    @Autowired
    TransactionTemplate tx;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeAll
    static void needDatabase() {
        assumeTrue(TestDb.available(), "No test database. Create one to run these tests.");
    }

    private record Opened(Long customerId, String number) {
    }

    private Opened open(String home) {
        long customerId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 9_000_000_000L);
        AccountView view = accountService.openHomeAccount(customerId, home);
        return new Opened(customerId, view.accountNumber());
    }

    private void swap(Opened o, String currency, String park) {
        walletService.swap(o.customerId(), o.number(), currency, park);
    }

    private void fund(Opened o, String currency, long minor) {
        accountService.devFund(o.number(), o.customerId(), currency, minor);
    }

    /** currency -> status, for example {EUR=PARKED, NGN=ACTIVE}. */
    private Map<String, String> statuses(Opened o) {
        Map<String, String> result = new TreeMap<>();
        jdbc.queryForList("select w.currency, w.status from wallets w join accounts a on a.id = w.account_id"
                        + " where a.account_number = ?", o.number())
                .forEach(r -> result.put((String) r.get("currency"), (String) r.get("status")));
        return result;
    }

    private long balance(Opened o, String currency) {
        Long value = jdbc.queryForObject("select w.balance_minor from wallets w join accounts a on a.id = w.account_id"
                + " where a.account_number = ? and w.currency = ?", Long.class, o.number(), currency);
        return value == null ? 0L : value;
    }

    private long activeCount(Opened o) {
        return statuses(o).values().stream().filter("ACTIVE"::equals).count();
    }

    private static void assertCode(ThrowingCallable call, String code) {
        assertThatThrownBy(call).isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getCode()).isEqualTo(code));
    }

    @Test
    void aUsdHomeAccountCanFillItsFreeSlotWithoutParking() {                       // acceptance 2
        Opened o = open("USD");
        assertThat(activeCount(o)).isEqualTo(3);

        swap(o, "JPY", null);

        assertThat(statuses(o)).containsEntry("JPY", "ACTIVE");
        assertThat(activeCount(o)).isEqualTo(4);
        assertCode(() -> swap(o, "CHF", null), "PARK_REQUIRED");
    }

    @Test
    void aFifthCurrencyNeedsAParkChoiceAndParkingKeepsTheBalance() {              // acceptance 3
        Opened o = open("NGN");
        fund(o, "EUR", 1_000L);

        assertCode(() -> swap(o, "JPY", null), "PARK_REQUIRED");
        assertThat(activeCount(o)).isEqualTo(4);
        assertThat(statuses(o)).doesNotContainKey("JPY");

        swap(o, "JPY", "EUR");

        assertThat(statuses(o)).containsEntry("EUR", "PARKED").containsEntry("JPY", "ACTIVE");
        assertThat(activeCount(o)).isEqualTo(4);
        assertThat(balance(o, "EUR")).isEqualTo(1_000L);
    }

    @Test
    void aParkedWalletCanReceiveButNotSend() {                                    // acceptance 4
        Opened o = open("NGN");
        fund(o, "EUR", 1_000L);
        swap(o, "JPY", "EUR");

        fund(o, "EUR", 500L);                                                     // receiving is allowed

        assertThat(balance(o, "EUR")).isEqualTo(1_500L);
        assertCode(() -> tx.executeWithoutResult(s -> accountService.debit(o.number(), "EUR", 100L)),
                "WALLET_PARKED");
        // An active wallet passes the parked check; this one only fails for lack of money.
        assertCode(() -> tx.executeWithoutResult(s -> accountService.debit(o.number(), "USD", 100L)),
                "INSUFFICIENT_FUNDS");
        assertThat(balance(o, "EUR")).isEqualTo(1_500L);
    }

    @Test
    void swappingBackRestoresTheOldBalanceWithOneWalletPerCurrency() {            // acceptance 5
        Opened o = open("NGN");
        fund(o, "EUR", 1_000L);
        swap(o, "JPY", "EUR");
        fund(o, "EUR", 500L);

        swap(o, "EUR", "JPY");

        assertThat(statuses(o)).containsEntry("EUR", "ACTIVE").containsEntry("JPY", "PARKED");
        assertThat(balance(o, "EUR")).isEqualTo(1_500L);
        Integer eurWallets = jdbc.queryForObject("select count(*) from wallets w join accounts a on a.id = w.account_id"
                + " where a.account_number = ? and w.currency = 'EUR'", Integer.class, o.number());
        assertThat(eurWallets).isEqualTo(1);
    }

    @Test
    void theHomeWalletCannotBeParkedOrSwapped() {                                 // acceptance 6
        Opened full = open("NGN");
        assertCode(() -> swap(full, "JPY", "NGN"), "WALLET_HOME_FIXED");
        assertCode(() -> swap(full, "NGN", "EUR"), "WALLET_ALREADY_ACTIVE");

        Opened usdHome = open("USD");                                             // free slot: still refused
        assertCode(() -> swap(usdHome, "JPY", "USD"), "WALLET_HOME_FIXED");
        assertThat(statuses(full)).containsEntry("NGN", "ACTIVE");
        assertThat(statuses(usdHome)).containsEntry("USD", "ACTIVE");
    }

    @Test
    void parkingIsRefusedWhenASlotIsFree() {
        Opened o = open("USD");

        assertCode(() -> swap(o, "JPY", "EUR"), "PARK_NOT_NEEDED");
        assertThat(statuses(o)).containsEntry("EUR", "ACTIVE");
    }

    @Test
    void unknownAndExcludedCurrenciesCannotBeActivated() {
        Opened o = open("USD");

        assertCode(() -> swap(o, "IRR", null), "UNSUPPORTED_CURRENCY");
        assertCode(() -> swap(o, "ZZZ", null), "UNSUPPORTED_CURRENCY");
        assertThat(activeCount(o)).isEqualTo(3);
    }

    @Test
    void aFrozenAccountBlocksSwapsAndDebits() {
        Opened o = open("USD");
        jdbc.update("update accounts set status = 'FROZEN' where account_number = ?", o.number());

        assertCode(() -> swap(o, "JPY", null), "ACCOUNT_FROZEN");
        assertCode(() -> tx.executeWithoutResult(s -> accountService.debit(o.number(), "EUR", 1L)), "ACCOUNT_FROZEN");
        assertThat(activeCount(o)).isEqualTo(3);
    }

    @Test
    void anotherCustomerCannotSwapMyWallets() {
        Opened mine = open("USD");
        Opened other = open("USD");

        assertCode(() -> walletService.swap(other.customerId(), mine.number(), "JPY", null), "ACCOUNT_NOT_FOUND");
        assertThat(activeCount(mine)).isEqualTo(3);
    }

    @Test
    void everySwapIsWrittenToTheAuditLog() {
        Opened o = open("NGN");

        swap(o, "JPY", "EUR");
        swap(o, "EUR", "JPY");

        List<String> actions = jdbc.queryForList(
                "select action from audit_log where actor = ? and action like 'WALLET_%' order by id",
                String.class, "customer:" + o.customerId());
        assertThat(actions).containsExactly("WALLET_PARKED", "WALLET_ACTIVATED", "WALLET_PARKED", "WALLET_REACTIVATED");
    }

    @Test
    void twoSimultaneousSwapsNeverExceedFourActiveWallets() throws Exception {
        Opened o = open("USD");                                                   // 3 active, one free slot
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<String>> futures = new ArrayList<>();

        for (String currency : List.of("JPY", "CHF")) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                try {
                    swap(o, currency, null);
                    return "OK";
                } catch (ApiException e) {
                    return e.getCode();
                }
            }));
        }
        ready.await();
        go.countDown();

        List<String> results = new ArrayList<>();
        for (Future<String> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(results).containsExactlyInAnyOrder("OK", "PARK_REQUIRED");
        assertThat(activeCount(o)).isEqualTo(4);
    }

    @Test
    void fundingNonNairaWalletsKeepsTheLedgerBalanced() {
        Opened o = open("NGN");
        fund(o, "USD", 2_550L);
        fund(o, "EUR", 1_000L);
        swap(o, "JPY", "EUR");
        fund(o, "EUR", 250L);                                                     // a parked wallet, funded
        fund(o, "JPY", 700L);                                                     // JPY has no decimals

        assertThat(reconciliation.run().problems()).isEmpty();
    }
}