package com.academy.paybridge.remittance;

import com.academy.paybridge.remittance.api.VerificationSource;
import com.academy.paybridge.remittance.client.AccountNotFoundException;
import com.academy.paybridge.remittance.client.PayoutGateway;
import com.academy.paybridge.remittance.client.PayoutRequest;
import com.academy.paybridge.remittance.client.PayoutResult;
import com.academy.paybridge.remittance.client.ProviderRate;
import com.academy.paybridge.remittance.client.VerificationUnavailableException;
import com.academy.paybridge.remittance.client.VerifiedName;
import com.academy.paybridge.remittance.service.PayoutDestinationsService;
import com.academy.paybridge.remittance.service.PayoutProperties;
import com.academy.paybridge.remittance.service.PayoutProperties.BankField;
import com.academy.paybridge.remittance.service.PayoutProperties.Country;
import com.academy.paybridge.remittance.service.PayoutVerificationService;
import com.academy.paybridge.remittance.service.PayoutVerificationService.VerifyRequest;
import com.academy.paybridge.remittance.service.VerificationBudget;
import com.academy.paybridge.remittance.service.VerifiedAccount;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyInfo;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import com.academy.paybridge.transfer.api.LookupLimitApi;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/** R-VER-1 to 8 and acceptance 21b and 21c, with no Spring and no database. */
class PayoutVerificationServiceTest {

    private static final List<BankField> NG_FIELDS = List.of(
            new BankField("bankCode", "Bank code", true), new BankField("accountNumber", "Account number", true));
    private static final List<BankField> GB_FIELDS = List.of(
            new BankField("bankCode", "Sort code", true), new BankField("accountNumber", "Account number", true));

    private static final List<Country> COUNTRIES = List.of(
            new Country("NG", "Nigeria", "NGN", NG_FIELDS),
            new Country("GB", "United Kingdom", "GBP", GB_FIELDS),
            new Country("IR", "Iran", "IRR", NG_FIELDS));

    private final CurrencyRegistry registry = new CurrencyRegistry(List.of(
            new CurrencyInfo("NGN", "Naira", "N", 2, true), new CurrencyInfo("GBP", "Pound", "P", 2, true),
            new CurrencyInfo("IRR", "Rial", "R", 2, true)), List.of("IRR"));

    /** A partner we control: what it answers, and how many times it was asked. */
    private static final class StubGateway implements PayoutGateway {
        enum Answer { OK, NOT_FOUND, UNAVAILABLE }

        Answer answer = Answer.OK;
        int calls;

        @Override
        public VerifiedName verifyAccount(String country, String bankCode, String accountNumber) {
            calls++;
            return switch (answer) {
                case OK -> new VerifiedName("REAL NAME " + accountNumber.substring(accountNumber.length() - 4));
                case NOT_FOUND -> throw new AccountNotFoundException("nope");
                case UNAVAILABLE -> throw new VerificationUnavailableException("down");
            };
        }

        @Override public ProviderRate getRate(String s, String d) { throw new UnsupportedOperationException(); }
        @Override public PayoutResult initiatePayout(PayoutRequest r) { throw new UnsupportedOperationException(); }
        @Override public PayoutResult verifyPayout(String reference) { throw new UnsupportedOperationException(); }
    }

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-16T10:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final StubGateway gateway = new StubGateway();
    private final MutableClock clock = new MutableClock();
    private final AuditService audit = mock(AuditService.class);

    private PayoutVerificationService service(String mode, int dailyLimit, List<String> verifiable, LookupLimitApi limiter) {
        PayoutProperties props = new PayoutProperties("fake",
                new PayoutProperties.Flutterwave(null, "FLWSECK_TEST-abc", null, 0, 0, null, mode, 24, dailyLimit, verifiable),
                null, null, null, COUNTRIES);
        return new PayoutVerificationService(gateway, new PayoutDestinationsService(props, registry), props,
                new VerificationBudget(props, clock), limiter, audit);
    }

    private PayoutVerificationService service(String mode, int dailyLimit, List<String> verifiable) {
        return service(mode, dailyLimit, verifiable, id -> { });
    }

    private static VerifyRequest ng(String account) {
        return new VerifyRequest("NG", "NGN", "058", account, null);
    }

    private static VerifyRequest gb(String account) {
        return new VerifyRequest("GB", "GBP", "200000", account, null);
    }

    private static String codeOf(Throwable t) {
        return ((ApiException) t).getCode();
    }

    // ---------------------------------------------------------------- live

    @Test
    void liveReturnsTheRealNameWithItsSource() {                                    // acceptance 21b
        VerifiedAccount a = service("live", 0, List.of("NG")).verify(7L, ng("0123456789"));

        assertThat(a.accountName()).isEqualTo("REAL NAME 6789");
        assertThat(a.source()).isEqualTo(VerificationSource.FLUTTERWAVE);
        assertThat(a.verified()).isTrue();
        assertThat(a.label()).isEqualTo("Name verified");
        assertThat(a.accountMasked()).isEqualTo("****6789");
    }

    @Test
    void liveRefusesWhenThePartnerCannotVerify() {
        gateway.answer = StubGateway.Answer.UNAVAILABLE;

        assertThatThrownBy(() -> service("live", 0, List.of("NG")).verify(7L, ng("0123456789")))
                .isInstanceOf(ApiException.class).satisfies(e -> assertThat(codeOf(e)).isEqualTo("VERIFICATION_UNAVAILABLE"));
    }

    @Test
    void liveRefusesACountryThatCannotBeVerifiedWithoutCallingThePartner() {
        assertThatThrownBy(() -> service("live", 0, List.of("NG")).verify(7L, gb("12345678")))
                .isInstanceOf(ApiException.class).satisfies(e -> assertThat(codeOf(e)).isEqualTo("VERIFICATION_UNAVAILABLE"));
        assertThat(gateway.calls).isZero();
    }

    // ---------------------------------------------------------------- auto

    @Test
    void autoUsesTheRealAnswerWhenThePartnerCanGiveOne() {
        VerifiedAccount a = service("auto", 0, List.of("NG")).verify(7L, ng("0123456789"));

        assertThat(a.source()).isEqualTo(VerificationSource.FLUTTERWAVE);
        assertThat(gateway.calls).isEqualTo(1);
    }

    @Test
    void autoFallsBackToALabelledSimulatedNameForAnUnsupportedCorridor() {         // acceptance 21c
        VerifiedAccount a = service("auto", 0, List.of("NG")).verify(7L, gb("12345678"));

        assertThat(a.accountName()).isEqualTo("TEST ACCOUNT 5678");
        assertThat(a.source()).isEqualTo(VerificationSource.SIMULATED);
        assertThat(a.verified()).isFalse();
        assertThat(a.label()).isEqualTo("name not verified (test mode)");
        assertThat(gateway.calls).as("no wasted call (R-VER-8)").isZero();
    }

    @Test
    void autoFallsBackWhenThePartnerIsUnavailable() {                               // acceptance 21c
        gateway.answer = StubGateway.Answer.UNAVAILABLE;

        VerifiedAccount a = service("auto", 0, List.of("NG")).verify(7L, ng("0123456789"));

        assertThat(a.source()).isEqualTo(VerificationSource.SIMULATED);
        assertThat(a.accountName()).isEqualTo("TEST ACCOUNT 6789");
        assertThat(gateway.calls).isEqualTo(1);
    }

    @Test
    void autoFallsBackOnceTheDailyAllowanceIsSpentAndNeverCallsThePartnerAgain() {  // acceptance 21c
        PayoutVerificationService s = service("auto", 2, List.of("NG"));

        assertThat(s.verify(7L, ng("0123456701")).source()).isEqualTo(VerificationSource.FLUTTERWAVE);
        assertThat(s.verify(7L, ng("0123456702")).source()).isEqualTo(VerificationSource.FLUTTERWAVE);
        VerifiedAccount third = s.verify(7L, ng("0123456703"));

        assertThat(third.source()).isEqualTo(VerificationSource.SIMULATED);
        assertThat(gateway.calls).isEqualTo(2);
    }

    @Test
    void theAllowanceStartsAgainOnTheNextUtcDay() {
        PayoutVerificationService s = service("auto", 1, List.of("NG"));
        s.verify(7L, ng("0123456701"));
        assertThat(s.verify(7L, ng("0123456702")).source()).isEqualTo(VerificationSource.SIMULATED);

        clock.now = Instant.parse("2026-10-17T00:00:01Z");

        assertThat(s.verify(7L, ng("0123456703")).source()).isEqualTo(VerificationSource.FLUTTERWAVE);
    }

    @Test
    void aPartnerThatSaysNotFoundRefusesAndNeverFallsBack() {                       // acceptance 21b, R-VER-6
        gateway.answer = StubGateway.Answer.NOT_FOUND;

        assertThatThrownBy(() -> service("auto", 0, List.of("NG")).verify(7L, ng("0123456789")))
                .isInstanceOf(ApiException.class).satisfies(e -> assertThat(codeOf(e)).isEqualTo("ACCOUNT_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- simulated

    @Test
    void simulatedNeverCallsThePartnerAndIsDeterministic() {
        PayoutVerificationService s = service("simulated", 0, List.of("NG"));

        VerifiedAccount a = s.verify(7L, ng("0123456789"));
        VerifiedAccount again = s.verify(7L, ng("0123456789"));

        assertThat(a).isEqualTo(again);
        assertThat(a.accountName()).isEqualTo("TEST ACCOUNT 6789");
        assertThat(a.source()).isEqualTo(VerificationSource.SIMULATED);
        assertThat(gateway.calls).isZero();
    }

    @Test
    void anAccountStartingWithTwoZerosIsNotFoundWhenSimulated() {                   // R-VER-4
        assertThatThrownBy(() -> service("simulated", 0, List.of("NG")).verify(7L, ng("0012345678")))
                .isInstanceOf(ApiException.class).satisfies(e -> assertThat(codeOf(e)).isEqualTo("ACCOUNT_NOT_FOUND"));
        assertThatThrownBy(() -> service("auto", 0, List.of("NG")).verify(7L, gb("0012345678")))   // fallback is simulated too
                .isInstanceOf(ApiException.class).satisfies(e -> assertThat(codeOf(e)).isEqualTo("ACCOUNT_NOT_FOUND"));
    }

    // ---------------------------------------------------------------- cache and audit

    @Test
    void aNameAlreadyVerifiedIsRememberedAndCostsNothingTheSecondTime() {          // R-VER-5
        PayoutVerificationService s = service("auto", 1, List.of("NG"));

        VerifiedAccount first = s.verify(7L, ng("0123456789"));
        VerifiedAccount second = s.verify(7L, ng("0123456789"));

        assertThat(second.accountName()).isEqualTo(first.accountName());
        assertThat(second.source()).isEqualTo(VerificationSource.FLUTTERWAVE);
        assertThat(gateway.calls).isEqualTo(1);
    }

    @Test
    void aRememberedNameExpiresAfterTheConfiguredHours() {
        PayoutVerificationService s = service("auto", 0, List.of("NG"));
        s.verify(7L, ng("0123456789"));

        clock.now = clock.now.plusSeconds(24 * 3600 + 1);
        s.verify(7L, ng("0123456789"));

        assertThat(gateway.calls).isEqualTo(2);
    }

    @Test
    void aSimulatedNameIsNeverRememberedAsIfItWereReal() {
        PayoutVerificationService s = service("auto", 0, List.of("NG"));
        gateway.answer = StubGateway.Answer.UNAVAILABLE;
        assertThat(s.verify(7L, ng("0123456789")).source()).isEqualTo(VerificationSource.SIMULATED);

        gateway.answer = StubGateway.Answer.OK;                                     // the partner recovers
        VerifiedAccount later = s.verify(7L, ng("0123456789"));

        assertThat(later.source()).isEqualTo(VerificationSource.FLUTTERWAVE);
    }

    @Test
    void auditNamesTheSourceAndNeverTheFullAccountNumber() {                        // R-VER-3, R-VER-5
        PayoutVerificationService s = service("auto", 0, List.of("NG"));
        s.verify(7L, ng("0123456789"));
        s.verify(7L, gb("5566778899"));

        ArgumentCaptor<String> action = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> details = ArgumentCaptor.forClass(String.class);
        verify(audit, org.mockito.Mockito.times(2)).recordIndependently(eq("customer:7"), action.capture(), isNull(), details.capture());

        assertThat(action.getAllValues()).containsExactly("PAYOUT_ACCOUNT_VERIFIED", "PAYOUT_ACCOUNT_SIMULATED");
        assertThat(details.getAllValues()).allSatisfy(d -> assertThat(d).contains("****").doesNotContain("0123456789")
                .doesNotContain("5566778899"));
        assertThat(details.getAllValues().get(1)).contains("SIMULATED");
    }

    // ---------------------------------------------------------------- limits and input

    @Test
    void theLookupLimiterRunsFirstSoNothingElseHappensWhenItSaysNo() {             // R-VER-7
        LookupLimitApi full = id -> { throw ApiException.tooManyRequests("TOO_MANY_LOOKUPS", "wait"); };

        assertThatThrownBy(() -> service("auto", 0, List.of("NG"), full).verify(7L, ng("0123456789")))
                .isInstanceOf(ApiException.class).satisfies(e -> assertThat(codeOf(e)).isEqualTo("TOO_MANY_LOOKUPS"));
        assertThat(gateway.calls).isZero();
        verify(audit, never()).recordIndependently(any(), any(), any(), any());
    }

    @Test
    void anUnsupportedOrExcludedCountryIsRefused() {
        PayoutVerificationService s = service("auto", 0, List.of("NG"));

        assertThatThrownBy(() -> s.verify(7L, new VerifyRequest("ZZ", null, "1", "123456", null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("COUNTRY_NOT_SUPPORTED"));
        assertThatThrownBy(() -> s.verify(7L, new VerifyRequest("IR", null, "1", "123456", null)))   // IRR is excluded
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("COUNTRY_NOT_SUPPORTED"));
    }

    @Test
    void aCurrencyThatDoesNotMatchTheCountryIsRefused() {
        assertThatThrownBy(() -> service("auto", 0, List.of("NG")).verify(7L,
                new VerifyRequest("NG", "USD", "058", "0123456789", null)))
                .satisfies(e -> assertThat(codeOf(e)).isEqualTo("CURRENCY_NOT_SUPPORTED"));
    }

    @Test
    void aMissingRequiredDetailIsRefused() {
        assertThatThrownBy(() -> service("auto", 0, List.of("NG")).verify(7L,
                new VerifyRequest("GB", "GBP", null, "12345678", Map.of())))
                .satisfies(e -> {
                    assertThat(codeOf(e)).isEqualTo("MISSING_BANK_FIELD");
                    assertThat(e.getMessage()).contains("Sort code");
                });
    }

    @Test
    void destinationsListOnlyCountriesWithAUsableCurrency() {
        PayoutProperties props = new PayoutProperties("fake", null, null, null, null, COUNTRIES);

        List<String> codes = new PayoutDestinationsService(props, registry).countries().stream().map(Country::code).toList();

        assertThat(codes).containsExactly("NG", "GB");
    }
}