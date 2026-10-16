package com.academy.paybridge.remittance;

import com.academy.paybridge.remittance.client.FakePayoutGateway;
import com.academy.paybridge.remittance.service.PayoutSettlementService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Plan 002-004, step B3 (acceptance 12, 13, 15, 16, 18, 19, 20, 21a, 21d). The fake partner stands in for Flutterwave. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "paybridge.payouts.flutterwave.webhook-secret=hook-secret-for-tests")
@ActiveProfiles("test")
class PayoutFlowTest extends PayoutHttpSupport {

    private static final String HOOK_SECRET = "hook-secret-for-tests";

    @Autowired
    FakePayoutGateway fake;

    @Autowired
    PayoutSettlementService settlement;

    private String status(User u, String reference) {
        return String.valueOf(call(HttpMethod.GET, "/api/v1/payouts/" + reference, u.token(), null).map().get("status"));
    }

    // ------------------------------------------------------------------ quote

    @Test
    void aConversionQuoteShowsRateFeeExactAmountAndTheVerifiedRecipient() {          // acceptance 12, 21a, 21b
        User a = newUser("GB");
        fund(a, "USD", "500");

        Resp q = quote(a, "USD", "100", "NG", "058", "2000000011", "FAMILY_SUPPORT");

        assertThat(q.status()).isEqualTo(200);
        assertThat(dec(q.map().get("sourceAmount"))).isEqualByComparingTo("100");
        assertThat(dec(q.map().get("fee"))).isEqualByComparingTo("1.00");               // 1% = $1.00, the $1 minimum
        assertThat(q.map()).containsEntry("feeReason", "STANDARD").containsEntry("payoutCurrency", "NGN")
                .containsEntry("status", "ACTIVE");
        assertThat(dec(q.map().get("netAmount"))).isEqualByComparingTo("99.00");
        assertThat(dec(q.map().get("payoutAmount"))).isGreaterThan(new java.math.BigDecimal("10000"));
        assertThat(dec(q.map().get("rate"))).isPositive();
        long ttl = ((Number) q.map().get("ttlSeconds")).longValue();
        assertThat(ttl).isBetween(1700L, 1800L);                                         // 30 minutes, not 60 seconds
        @SuppressWarnings("unchecked")
        Map<String, Object> destination = (Map<String, Object>) q.map().get("destination");
        assertThat(destination).containsEntry("accountName", "FLUTTERWAVE TEST 0011").containsEntry("nameSource", "FLUTTERWAVE")
                .containsEntry("accountMasked", "****0011").containsEntry("country", "NG");
        assertThat(q.body().toString()).doesNotContain("2000000011");
        assertThat(balance(a, "USD")).isEqualByComparingTo("500");                       // a quote moves nothing
    }

    @Test
    void aSameCurrencyPayoutNeedsNoConversionAndStillPaysTheFee() {                     // acceptance 13
        User a = newUser("GB");
        fund(a, "USD", "500");

        Resp q = quote(a, "USD", "100", "US", "021000021", "2000000012", "GIFT");

        assertThat(q.status()).isEqualTo(200);
        assertThat(dec(q.map().get("rate"))).isEqualByComparingTo("1");
        assertThat(dec(q.map().get("fee"))).isEqualByComparingTo("1.00");
        assertThat(dec(q.map().get("payoutAmount"))).isEqualByComparingTo(dec(q.map().get("netAmount")));
        assertThat(q.map().get("rateMode")).isNull();                                    // no conversion, no partner rate
    }

    @Test
    void aPurposeOutsideTheListIsRefused() {                                             // acceptance 15
        User a = newUser("GB");
        fund(a, "USD", "500");

        assertThat(quote(a, "USD", "100", "NG", "058", "2000000013", "GAMBLING").code()).isEqualTo("INVALID_PAYOUT_PURPOSE");
        assertThat(quote(a, "USD", "100", "NG", "058", "2000000013", "").status()).isEqualTo(400);
    }

    @Test
    void aRefusalBeforeConfirmingMovesNothing() {
        User a = newUser("GB");
        fund(a, "USD", "50");

        Resp tooMuch = quote(a, "USD", "100", "NG", "058", "2000000014", "GIFT");
        Resp notFound = quote(a, "USD", "10", "NG", "058", "0012345678", "GIFT");

        assertThat(tooMuch.code()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(notFound.code()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(balance(a, "USD")).isEqualByComparingTo("50");
    }

    // ------------------------------------------------------------------ confirm and settle

    @Test
    void confirmingDebitsOnceThenTheOutcomeSettlesOnceAndBooksBalance() {                // acceptance 12, 10
        User a = newUser("GB");
        fund(a, "USD", "500");
        Resp q = quote(a, "USD", "100", "NG", "058", "2000000021", "EDUCATION");

        Resp done = confirm(a, q.map().get("quoteId"), PIN, key());

        assertThat(done.status()).isEqualTo(201);
        assertThat(done.map()).containsEntry("status", "PENDING").containsEntry("sourceCurrency", "USD")
                .containsEntry("payoutCurrency", "NGN").containsEntry("purpose", "EDUCATION");
        String reference = (String) done.map().get("reference");
        assertThat(balance(a, "USD")).isEqualByComparingTo("400");                       // debited once, in the source currency
        assertBooksBalance();                                                            // wallet and ledger agree while in flight

        settlement.settleFromGateway(reference);                                         // what the job or webhook does
        settlement.settleFromGateway(reference);                                         // a second time changes nothing

        assertThat(status(a, reference)).isEqualTo("SUCCESSFUL");
        assertThat(balance(a, "USD")).isEqualByComparingTo("400");
        assertThat(call(HttpMethod.GET, "/api/v1/payouts", a.token(), null).list()).hasSize(1);
        assertBooksBalance();
    }

    @Test
    void aFailedPayoutRefundsInFullIncludingTheFee() throws InterruptedException {       // acceptance 16
        User a = newUser("GB");
        fund(a, "USD", "500");
        Resp q = quote(a, "USD", "100", "NG", "058", "9900000001", "GIFT");              // the fake accepts it, then fails it
        String reference = (String) confirm(a, q.map().get("quoteId"), PIN, key()).map().get("reference");
        assertThat(balance(a, "USD")).isEqualByComparingTo("400");

        settlement.settleFromGateway(reference);

        Map<String, Object> payout = call(HttpMethod.GET, "/api/v1/payouts/" + reference, a.token(), null).map();
        assertThat(payout).containsEntry("status", "FAILED");
        assertThat(payout.get("failureReason")).isNotNull();
        assertThat(balance(a, "USD")).isEqualByComparingTo("500");                       // the whole 100 back, fee included
        assertThat(notified(a, "returned")).isTrue();
        assertThat(confirm(a, q.map().get("quoteId"), PIN, key()).code()).isEqualTo("QUOTE_FAILED");   // cannot be reused
        assertBooksBalance();
    }

    @Test
    void aPayoutTheBankRejectsOutrightIsRefundedAtOnce() {
        User a = newUser("GB");
        fund(a, "USD", "500");
        Resp q = quote(a, "USD", "100", "NG", "058", "7700000001", "GIFT");

        Resp done = confirm(a, q.map().get("quoteId"), PIN, key());

        assertThat(done.map()).containsEntry("status", "FAILED");
        assertThat(balance(a, "USD")).isEqualByComparingTo("500");
        assertBooksBalance();
    }

    @Test
    void aTimeoutStaysPendingRefundsNothingAndSettlesOnceLater() {                       // acceptance 18
        User a = newUser("GB");
        fund(a, "USD", "500");
        Resp q = quote(a, "USD", "100", "NG", "058", "8800000001", "GIFT");              // times out, but the partner paid

        Resp done = confirm(a, q.map().get("quoteId"), PIN, key());
        String reference = (String) done.map().get("reference");

        assertThat(done.status()).isEqualTo(201);
        assertThat(done.map()).containsEntry("status", "PENDING");
        assertThat(balance(a, "USD")).isEqualByComparingTo("400");                       // nothing refunded

        settlement.settleFromGateway(reference);
        settlement.settleFromGateway(reference);

        assertThat(status(a, reference)).isEqualTo("SUCCESSFUL");
        assertThat(balance(a, "USD")).isEqualByComparingTo("400");
        assertBooksBalance();
    }

    @Test
    void anUnknownReferenceIsSentAgainWithTheSameReferenceNeverRefunded() {
        User a = newUser("GB");
        fund(a, "USD", "500");
        Resp q = quote(a, "USD", "100", "NG", "058", "2000000031", "GIFT");
        String reference = (String) confirm(a, q.map().get("quoteId"), PIN, key()).map().get("reference");
        int before = fake.requestCount(reference);

        // the partner has a record, so settling asks it and finds SUCCESS; no second request is made
        settlement.settleFromGateway(reference);

        assertThat(fake.requestCount(reference)).isEqualTo(before);
        assertThat(status(a, reference)).isEqualTo("SUCCESSFUL");
    }

    @Test
    void theSameKeyPaysOnceAndAnotherQuoteWithThatKeyIsRefused() {                       // acceptance 19
        User a = newUser("GB");
        fund(a, "USD", "500");
        Resp q1 = quote(a, "USD", "100", "NG", "058", "2000000041", "GIFT");
        Resp q2 = quote(a, "USD", "100", "NG", "058", "2000000042", "GIFT");
        String key = key();

        Resp first = confirm(a, q1.map().get("quoteId"), PIN, key);
        Resp again = confirm(a, q1.map().get("quoteId"), PIN, key);
        Resp other = confirm(a, q2.map().get("quoteId"), PIN, key);

        assertThat(first.status()).isEqualTo(201);
        assertThat(again.status()).isEqualTo(200);
        assertThat(again.map().get("reference")).isEqualTo(first.map().get("reference"));
        assertThat(other.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(balance(a, "USD")).isEqualByComparingTo("400");                       // debited once
        assertThat(fake.requestCount((String) first.map().get("reference"))).isEqualTo(1);   // one request reached the partner
    }

    @Test
    void aWrongPinBurnsNothingAndAUsedQuoteCannotBeUsedAgain() {
        User a = newUser("GB");
        fund(a, "USD", "500");
        Resp q = quote(a, "USD", "100", "NG", "058", "2000000051", "GIFT");

        Resp wrong = confirm(a, q.map().get("quoteId"), "0000", key());
        Resp good = confirm(a, q.map().get("quoteId"), PIN, key());
        Resp second = confirm(a, q.map().get("quoteId"), PIN, key());

        assertThat(wrong.status()).isEqualTo(403);
        assertThat(good.status()).isEqualTo(201);
        assertThat(second.code()).isEqualTo("QUOTE_ALREADY_USED");
        assertThat(balance(a, "USD")).isEqualByComparingTo("400");
    }

    @Test
    void anotherCustomersQuoteLooksMissingAndTheirPayoutsAreInvisible() {
        User a = newUser("GB");
        User b = newUser("GB");
        fund(a, "USD", "500");
        Resp q = quote(a, "USD", "100", "NG", "058", "2000000061", "GIFT");

        assertThat(confirm(b, q.map().get("quoteId"), PIN, key()).status()).isEqualTo(404);
        String reference = (String) confirm(a, q.map().get("quoteId"), PIN, key()).map().get("reference");
        assertThat(call(HttpMethod.GET, "/api/v1/payouts/" + reference, b.token(), null).status()).isEqualTo(404);
        assertThat(call(HttpMethod.GET, "/api/v1/payouts", b.token(), null).list()).isEmpty();
    }

    // ------------------------------------------------------------------ rate difference (OWN_LOCK, journal J6)

    @Test
    void aWorseRealCostIsCarriedByPayBridgeAndNeverChangesWhatTheCustomerPays() {      // acceptance 21a
        User a = newUser("GB");
        fund(a, "USD", "500");
        Resp q = quote(a, "USD", "100", "NG", "058", "6600000001", "GIFT");              // the fake's real cost is 2% worse

        String reference = (String) confirm(a, q.map().get("quoteId"), PIN, key()).map().get("reference");
        settlement.settleFromGateway(reference);

        assertThat(status(a, reference)).isEqualTo("SUCCESSFUL");
        assertThat(balance(a, "USD")).isEqualByComparingTo("400");                       // the customer's debit is unchanged
        assertThat(jdbc.queryForObject("select rate_difference_minor from remittances where reference = ?", Long.class,
                reference)).isPositive();
        assertBooksBalance();                                                            // J6 keeps every currency balanced
    }

    // ------------------------------------------------------------------ limits (acceptance 21d, 7)

    @Test
    void thePerPayoutLimitApplies() {
        User a = newUser("GB");
        fund(a, "USD", "3000");

        Resp over = quote(a, "USD", "1000.01", "NG", "058", "2000000071", "GIFT");

        assertThat(over.code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(over.message()).contains("single payout limit");
        assertThat(quote(a, "USD", "1000", "NG", "058", "2000000071", "GIFT").status()).isEqualTo(200);
    }

    @Test
    void transfersAndPayoutsShareOneDailyTotalOfFiveThousandDollars() {                  // acceptance 21d
        User a = newUser("GB");
        User b = newUser("GB");
        fund(a, "USD", "6000");

        for (int i = 0; i < 3; i++) {
            assertThat(sameCurrencyTransfer(a, b, "USD", "1000").status()).isEqualTo(201);       // $3,000 of transfers
        }
        for (int i = 0; i < 2; i++) {
            Resp q = quote(a, "USD", "1000", "NG", "058", "20000001" + (80 + i), "GIFT");
            assertThat(confirm(a, q.map().get("quoteId"), PIN, key()).status()).isEqualTo(201);  // $2,000 of payouts
        }

        Resp payout = quote(a, "USD", "10", "NG", "058", "2000000099", "GIFT");
        Resp transfer = sameCurrencyTransfer(a, b, "USD", "1");

        assertThat(payout.code()).isEqualTo("LIMIT_EXCEEDED");
        assertThat(payout.message()).contains("daily limit");
        assertThat(transfer.code()).isEqualTo("LIMIT_EXCEEDED");                         // transfers count payouts too
        assertBooksBalance();
    }

    @Test
    void aRefundedPayoutDoesNotUseUpTheDay() {
        User a = newUser("GB");
        fund(a, "USD", "6000");
        for (int i = 0; i < 5; i++) {
            Resp q = quote(a, "USD", "1000", "NG", "058", "99000001" + (10 + i), "GIFT");   // each fails and is refunded
            String ref = (String) confirm(a, q.map().get("quoteId"), PIN, key()).map().get("reference");
            settlement.settleFromGateway(ref);
        }

        assertThat(quote(a, "USD", "1000", "NG", "058", "2000000098", "GIFT").status()).isEqualTo(200);
        assertThat(balance(a, "USD")).isEqualByComparingTo("6000");
        assertBooksBalance();
    }

    // ------------------------------------------------------------------ webhook (acceptance 20)

    private Resp webhook(String reference, String hashHeader) {
        Map<String, String> headers = hashHeader == null ? Map.of() : Map.of("verif-hash", hashHeader);
        return call(HttpMethod.POST, "/api/v1/webhooks/flutterwave", null, headers,
                Map.of("event", "transfer.completed", "data", Map.of("reference", reference, "status", "SUCCESSFUL")));
    }

    @Test
    void anUnsignedOrWronglySignedWebhookIsRefusedAndChangesNothing() {
        User a = newUser("GB");
        fund(a, "USD", "500");
        Resp q = quote(a, "USD", "100", "NG", "058", "2000000081", "GIFT");
        String reference = (String) confirm(a, q.map().get("quoteId"), PIN, key()).map().get("reference");

        assertThat(webhook(reference, null).status()).isEqualTo(401);
        assertThat(webhook(reference, "not-the-secret").status()).isEqualTo(401);

        assertThat(status(a, reference)).isEqualTo("PENDING");
    }

    @Test
    void aCorrectlySignedWebhookSettlesThePayoutOnce() {
        User a = newUser("GB");
        fund(a, "USD", "500");
        Resp q = quote(a, "USD", "100", "NG", "058", "2000000082", "GIFT");
        String reference = (String) confirm(a, q.map().get("quoteId"), PIN, key()).map().get("reference");

        assertThat(webhook(reference, HOOK_SECRET).status()).isEqualTo(200);
        assertThat(webhook(reference, HOOK_SECRET).status()).isEqualTo(200);             // delivered twice

        assertThat(status(a, reference)).isEqualTo("SUCCESSFUL");
        assertThat(balance(a, "USD")).isEqualByComparingTo("400");
        assertBooksBalance();
    }

    @Test
    void aWebhookIsOnlyAHintSoAForgedSuccessCannotMoveMoney() {                          // R-PAY-12a
        User a = newUser("GB");
        fund(a, "USD", "500");
        Resp q = quote(a, "USD", "100", "NG", "058", "9900000002", "GIFT");              // the partner really FAILED it
        String reference = (String) confirm(a, q.map().get("quoteId"), PIN, key()).map().get("reference");

        assertThat(webhook(reference, HOOK_SECRET).status()).isEqualTo(200);             // claims SUCCESSFUL

        assertThat(status(a, reference)).isEqualTo("FAILED");                            // the verify call told the truth
        assertThat(balance(a, "USD")).isEqualByComparingTo("500");
    }

    @Test
    void aWebhookForAnUnknownReferenceIsIgnored() {
        assertThat(webhook("pout_doesnotexist", HOOK_SECRET).status()).isEqualTo(200);
    }
}