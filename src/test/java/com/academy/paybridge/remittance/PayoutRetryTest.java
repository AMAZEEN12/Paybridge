package com.academy.paybridge.remittance;

import com.academy.paybridge.remittance.client.FakePayoutGateway;
import com.academy.paybridge.remittance.service.PayoutSettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan 002-004, step B5 (acceptance 17): retries switched ON, with no waiting between attempts. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"paybridge.payouts.retry.enabled=true", "paybridge.payouts.retry.spacing=PT0S"})
@ActiveProfiles("test")
class PayoutRetryTest extends PayoutHttpSupport {

    @Autowired
    FakePayoutGateway fake;

    @Autowired
    PayoutSettlementService settlement;

    private String status(User u, String reference) {
        return String.valueOf(call(HttpMethod.GET, "/api/v1/payouts/" + reference, u.token(), null).map().get("status"));
    }

    private String dbStatus(String reference) {
        return jdbc.queryForObject("select status from remittances where reference = ?", String.class, reference);
    }

    /** What the job does: settle until the payout is final (a safety limit stops a loop). */
    private void settleUntilFinal(String reference) {
        for (int i = 0; i < 20; i++) {
            settlement.settleFromGateway(reference);
            String s = dbStatus(reference);
            if (s.equals("SUCCESSFUL") || s.equals("FAILED")) {
                return;
            }
        }
    }

    @Test
    void convertedButNotPaidKeepsTheMoneyThenARetryPays() {
        User a = newUser("GB");
        fund(a, "USD", "500");
        Resp q = quote(a, "USD", "100", "NG", "058", "5600000001", "GIFT");              // fails once, the first retry pays
        String reference = (String) confirm(a, q.map().get("quoteId"), PIN, key()).map().get("reference");

        settlement.settleFromGateway(reference);                                         // the original fails: J2, now retrying

        assertThat(dbStatus(reference)).isEqualTo("RETRYING");
        assertThat(status(a, reference)).isEqualTo("PENDING");                           // O9: the customer sees PENDING only
        assertThat(balance(a, "USD")).isEqualByComparingTo("400");                       // not refunded
        assertBooksBalance();

        settleUntilFinal(reference);

        assertThat(status(a, reference)).isEqualTo("SUCCESSFUL");
        assertThat(balance(a, "USD")).isEqualByComparingTo("400");
        assertThat(jdbc.queryForObject("select attempts from remittances where reference = ?", Integer.class, reference))
                .isEqualTo(1);
        assertThat(fake.requestCount(reference)).isEqualTo(1);                           // the first request was never replayed
        assertThat(fake.requestCount(reference + "-R1")).isEqualTo(1);                   // the retry has its own reference
        assertBooksBalance();
    }

    @Test
    void afterThreeFailedRetriesTheCustomerIsRepaidInFull() throws InterruptedException {
        User a = newUser("GB");
        fund(a, "USD", "500");
        Resp q = quote(a, "USD", "100", "NG", "058", "5500000001", "GIFT");              // every retry fails too
        String reference = (String) confirm(a, q.map().get("quoteId"), PIN, key()).map().get("reference");

        settleUntilFinal(reference);

        assertThat(status(a, reference)).isEqualTo("FAILED");
        assertThat(balance(a, "USD")).isEqualByComparingTo("500");                       // everything back, fee included (J5)
        assertThat(jdbc.queryForObject("select attempts from remittances where reference = ?", Integer.class, reference))
                .isEqualTo(3);
        assertThat(fake.requestCount(reference + "-R3")).isEqualTo(1);
        assertThat(fake.requestCount(reference + "-R4")).isZero();                       // never more than 3
        assertThat(notified(a, "returned")).isTrue();
        assertBooksBalance();                                                            // J2 then J5 leave every currency balanced
    }

    @Test
    void settlingAgainAfterTheEndChangesNothing() {
        User a = newUser("GB");
        fund(a, "USD", "500");
        Resp q = quote(a, "USD", "100", "NG", "058", "5600000002", "GIFT");
        String reference = (String) confirm(a, q.map().get("quoteId"), PIN, key()).map().get("reference");
        settleUntilFinal(reference);

        settlement.settleFromGateway(reference);
        settlement.settleFromGateway(reference + "-R1");                                 // a webhook for the retry reference

        assertThat(status(a, reference)).isEqualTo("SUCCESSFUL");
        assertThat(balance(a, "USD")).isEqualByComparingTo("400");
        assertBooksBalance();
    }
}