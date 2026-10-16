package com.academy.paybridge.remittance.service;

import com.academy.paybridge.remittance.api.VerificationSource;
import com.academy.paybridge.remittance.client.AccountNotFoundException;
import com.academy.paybridge.remittance.client.PayoutGateway;
import com.academy.paybridge.remittance.client.VerificationUnavailableException;
import com.academy.paybridge.remittance.client.VerifiedName;
import com.academy.paybridge.shared.audit.AuditService;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.transfer.api.LookupLimitApi;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * "Whose account is this?" before a payout quote (R-VER). Deliberately NOT transactional: it may call the partner
 * over the network, and no database transaction may be open during that.
 *
 * Fall back to a simulated name only when the partner CANNOT check (unsupported corridor, unavailable, allowance
 * spent) and only in auto mode. A partner that says the account does not exist refuses the quote, always (R-VER-6).
 */
@Service
public class PayoutVerificationService {

    /** What the sender typed. {@code bankCode} and {@code accountNumber} are the two main details; others go in {@code extra}. */
    public record VerifyRequest(String country, String currency, String bankCode, String accountNumber,
                                Map<String, String> extra) {
    }

    private final PayoutGateway gateway;
    private final PayoutDestinationsService destinations;
    private final PayoutProperties props;
    private final VerificationBudget budget;
    private final LookupLimitApi limiter;
    private final AuditService audit;

    public PayoutVerificationService(PayoutGateway gateway, PayoutDestinationsService destinations,
                                     PayoutProperties props, VerificationBudget budget, LookupLimitApi limiter,
                                     AuditService audit) {
        this.gateway = gateway;
        this.destinations = destinations;
        this.props = props;
        this.budget = budget;
        this.limiter = limiter;
        this.audit = audit;
    }

    public VerifiedAccount verify(Long customerId, VerifyRequest r) {
        limiter.check(customerId);                                   // first: a wrong guess still uses up an attempt

        PayoutProperties.Country country = destinations.require(r.country());
        if (r.currency() != null && !r.currency().isBlank() && !country.currency().equalsIgnoreCase(r.currency().trim())) {
            throw ApiException.unprocessable("CURRENCY_NOT_SUPPORTED",
                    "Payouts to " + country.name() + " are made in " + country.currency() + ".");
        }
        requireFields(country, r);

        String bank = r.bankCode() == null ? "" : r.bankCode().trim();
        String account = r.accountNumber().trim();
        VerifyMode mode = VerifyMode.parse(props.flutterwave().verifyMode());

        VerifiedAccount result = mode == VerifyMode.SIMULATED
                ? simulate(country, account)
                : viaPartner(mode, country, bank, account);

        // A simulated answer is recorded under its own name, so it can never pass for a real verification (R-VER-3).
        audit.recordIndependently("customer:" + customerId,
                result.verified() ? "PAYOUT_ACCOUNT_VERIFIED" : "PAYOUT_ACCOUNT_SIMULATED", null,
                "country " + country.code() + " account " + result.accountMasked() + " source " + result.source());
        return result;
    }

    private VerifiedAccount viaPartner(VerifyMode mode, PayoutProperties.Country country, String bank, String account) {
        String key = VerificationBudget.key(country.code(), bank, account);

        var remembered = budget.remembered(key);                     // R-VER-5: no second lookup, no second allowance
        if (remembered.isPresent()) {
            return real(country, account, remembered.get());
        }
        if (!props.flutterwave().canVerify(country.code())) {        // R-VER-8: no wasted call
            return cannotVerify(mode, country, account, "this country cannot be verified");
        }
        if (!budget.trySpend()) {
            return cannotVerify(mode, country, account, "today's verification allowance is spent");
        }
        try {
            VerifiedName name = gateway.verifyAccount(country.code(), bank, account);
            budget.remember(key, name.accountName());
            return real(country, account, name.accountName());
        } catch (AccountNotFoundException e) {
            throw notFound();                                        // never falls back (R-VER-6)
        } catch (VerificationUnavailableException e) {
            return cannotVerify(mode, country, account, "the partner cannot verify right now");
        }
    }

    private VerifiedAccount cannotVerify(VerifyMode mode, PayoutProperties.Country country, String account, String why) {
        if (mode == VerifyMode.LIVE) {
            throw ApiException.unprocessable("VERIFICATION_UNAVAILABLE",
                    "We cannot verify this account right now (" + why + "). Please try again later.");
        }
        return simulate(country, account);                           // auto: test mode only, labelled
    }

    /** R-VER-4: an account starting 00 is "not found"; any other gets TEST ACCOUNT plus its last four digits. */
    private VerifiedAccount simulate(PayoutProperties.Country country, String account) {
        if (account.startsWith("00")) {
            throw notFound();
        }
        return new VerifiedAccount("TEST ACCOUNT " + lastFour(account), VerificationSource.SIMULATED,
                VerifiedAccount.LABEL_SIMULATED, mask(account), country.code(), country.currency());
    }

    private static VerifiedAccount real(PayoutProperties.Country country, String account, String name) {
        return new VerifiedAccount(name, VerificationSource.FLUTTERWAVE, VerifiedAccount.LABEL_VERIFIED,
                mask(account), country.code(), country.currency());
    }

    private static void requireFields(PayoutProperties.Country country, VerifyRequest r) {
        for (PayoutProperties.BankField field : country.bankFields()) {
            if (!field.required()) {
                continue;
            }
            String value = switch (field.name()) {
                case "bankCode" -> r.bankCode();
                case "accountNumber" -> r.accountNumber();
                default -> r.extra() == null ? null : r.extra().get(field.name());
            };
            if (value == null || value.isBlank()) {
                throw ApiException.badRequest("MISSING_BANK_FIELD", field.label() + " is required for " + country.name() + ".");
            }
        }
        if (r.accountNumber() == null || r.accountNumber().isBlank()) {
            throw ApiException.badRequest("MISSING_BANK_FIELD", "The account number is required.");
        }
    }

    private static ApiException notFound() {
        return ApiException.unprocessable("ACCOUNT_NOT_FOUND", "We could not find that bank account.");
    }

    /** Never the full number, anywhere: logs, audit, responses. */
    static String mask(String account) {
        return "****" + lastFour(account);
    }

    private static String lastFour(String account) {
        return account.length() <= 4 ? account : account.substring(account.length() - 4);
    }
}