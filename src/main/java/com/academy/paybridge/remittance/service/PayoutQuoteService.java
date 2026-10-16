package com.academy.paybridge.remittance.service;

import com.academy.paybridge.account.api.AccountApi;
import com.academy.paybridge.account.api.AccountView;
import com.academy.paybridge.account.api.WalletView;
import com.academy.paybridge.fx.api.PayoutQuoteApi;
import com.academy.paybridge.fx.api.PayoutQuoteApi.NewPayoutQuote;
import com.academy.paybridge.fx.api.PayoutQuoteApi.PayoutQuoteView;
import com.academy.paybridge.fx.api.RateApi;
import com.academy.paybridge.remittance.client.PayoutGateway;
import com.academy.paybridge.remittance.client.ProviderRate;
import com.academy.paybridge.remittance.client.RateUnavailableException;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.shared.money.CurrencyInfo;
import com.academy.paybridge.shared.money.CurrencyRegistry;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A payout quote (R-PAY-3 to R-PAY-5, R-PAY-17). Order matters: the cheap checks first, then the network calls
 * (verification, the partner's rate), and only then the save. No database transaction is open during a network call.
 */
@Service
public class PayoutQuoteService {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> RECIPIENT_TYPES = Set.of("INDIVIDUAL", "CORPORATE");

    /** The locked quote plus who the money is going to. */
    public record Result(PayoutQuoteView quote, VerifiedAccount recipient) {
    }

    public record CreateRequest(String sourceCurrency, BigDecimal amount, String country, String bankCode,
                                String accountNumber, Map<String, String> bankFields, String recipientType,
                                String purpose) {
    }

    private final AccountApi accounts;
    private final CurrencyRegistry currencies;
    private final PayoutDestinationsService destinations;
    private final PayoutVerificationService verification;
    private final PayoutGateway gateway;
    private final PayoutProperties props;
    private final PayoutPolicyService policy;
    private final RateApi rateApi;
    private final PayoutQuoteApi quoteApi;

    public PayoutQuoteService(AccountApi accounts, CurrencyRegistry currencies, PayoutDestinationsService destinations,
                              PayoutVerificationService verification, PayoutGateway gateway, PayoutProperties props,
                              PayoutPolicyService policy, RateApi rateApi, PayoutQuoteApi quoteApi) {
        this.accounts = accounts;
        this.currencies = currencies;
        this.destinations = destinations;
        this.verification = verification;
        this.gateway = gateway;
        this.props = props;
        this.policy = policy;
        this.rateApi = rateApi;
        this.quoteApi = quoteApi;
    }

    public Result create(Long customerId, CreateRequest request) {
        String source = normalise(request.sourceCurrency());
        CurrencyInfo sourceInfo = currencies.require(source);
        long sourceMinor = currencies.parse(request.amount(), source).minor();

        String purpose = normalise(request.purpose());
        if (!destinations.purposes().contains(purpose)) {                               // acceptance 15
            throw ApiException.badRequest("INVALID_PAYOUT_PURPOSE", "Choose a valid purpose for this payout.");
        }
        String recipientType = normalise(request.recipientType());
        if (!RECIPIENT_TYPES.contains(recipientType)) {
            throw ApiException.badRequest("INVALID_RECIPIENT_TYPE", "The recipient must be INDIVIDUAL or CORPORATE.");
        }

        PayoutProperties.Country country = destinations.require(request.country());
        String target = country.currency();
        CurrencyInfo targetInfo = currencies.require(target);

        policy.checkCorridor(customerId, country.code());                               // R-COR-4

        AccountView account = accounts.existingFor(customerId);
        if (accounts.isFrozen(account.accountNumber())) {
            throw ApiException.unprocessable("ACCOUNT_FROZEN", "This account is frozen. Unfreeze it to send money.");
        }
        WalletView wallet = accounts.findWallet(account.accountNumber(), source).orElseThrow(
                () -> ApiException.notFound("WALLET_NOT_FOUND", "You do not have a " + source + " wallet."));
        if ("PARKED".equals(wallet.status())) {
            throw ApiException.unprocessable("WALLET_PARKED", "This wallet is parked. Reactivate it to send from it.");
        }
        if (wallet.balanceMinor() < sourceMinor) {
            throw ApiException.unprocessable("INSUFFICIENT_FUNDS",
                    "You do not have enough money in your " + source + " wallet.");
        }

        // Network call 1: whose account is this? (R-VER)
        VerifiedAccount recipient = verification.verify(customerId, new PayoutVerificationService.VerifyRequest(
                country.code(), target, request.bankCode(), request.accountNumber(), request.bankFields()));

        // The mid-market snapshot supplies the USD equivalent and the $1 minimum. Stale rates refuse (R-LIM2-5).
        BigDecimal toUsd = rateApi.rateWithUsd(source, source).toUsd();

        // Network call 2: the partner's rate, only when a conversion is involved (acceptance 13).
        boolean conversion = !source.equals(target);
        ProviderRate providerRate = null;
        BigDecimal rate = BigDecimal.ONE;
        if (conversion) {
            try {
                providerRate = gateway.getRate(source, target);
            } catch (RateUnavailableException e) {                                      // never the mid-market rate (R-PAY-3b)
                throw ApiException.unprocessable("PAYOUT_RATE_UNAVAILABLE",
                        "A payout rate is not available right now. Please try again.");
            }
            rate = providerRate.rate();
            if (rate == null || rate.signum() <= 0) {
                throw ApiException.unprocessable("PAYOUT_RATE_UNAVAILABLE",
                        "A payout rate is not available right now. Please try again.");
            }
        }

        long feeMinor = PayoutMath.fee(sourceMinor, sourceInfo.minorUnits(), props.fee().rate(),
                props.fee().minimumUsd(), toUsd);
        long netMinor = sourceMinor - feeMinor;
        if (netMinor <= 0) {
            throw ApiException.unprocessable("AMOUNT_TOO_SMALL", "The amount is too small after the payout fee.");
        }
        long payoutMinor = conversion
                ? PayoutMath.convert(netMinor, sourceInfo.minorUnits(), targetInfo.minorUnits(), rate)
                : netMinor;
        if (payoutMinor <= 0) {
            throw ApiException.unprocessable("PAYOUT_AMOUNT_TOO_SMALL", "The amount is too small after conversion.");
        }
        long usdMinor = PayoutMath.usdEquivalent(sourceMinor, sourceInfo.minorUnits(), toUsd);

        policy.checkLimits(customerId, account.accountNumber(), usdMinor);              // R-LIM2-2

        boolean providerLock = conversion && "PROVIDER_LOCK".equals(props.flutterwave().rateMode());
        Instant now = Instant.now();
        PayoutQuoteView quote = quoteApi.create(new NewPayoutQuote(customerId, source, target, sourceMinor, feeMinor,
                netMinor, payoutMinor, rate, usdMinor,
                providerLock ? providerRate.expiresAt() : null,
                request.accountNumber().trim(), recipient.accountName(),
                request.bankCode() == null ? null : request.bankCode().trim(), country.code(),
                bankFieldsJson(request.bankFields()), recipientType, purpose, recipient.source().name(),
                conversion ? props.flutterwave().rateMode() : null,
                providerLock ? providerRate.providerRef() : null,
                providerLock ? providerRate.expiresAt() : null));
        return new Result(quote, recipient);
    }

    private static String bankFieldsJson(Map<String, String> fields) {
        return JSON.writeValueAsString(fields == null ? Map.of() : fields);
    }

    private static String normalise(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }
}