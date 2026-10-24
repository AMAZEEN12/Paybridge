package com.academy.paybridge.remittance.service;

import com.academy.paybridge.compliance.api.CorridorApi;
import com.academy.paybridge.compliance.api.CorridorResult;
import com.academy.paybridge.compliance.api.Decision;
import com.academy.paybridge.compliance.api.FraudResult;
import com.academy.paybridge.compliance.api.TransferLimitApi;
import com.academy.paybridge.compliance.api.TransferLimitContext;
import com.academy.paybridge.customer.api.CustomerApi;
import com.academy.paybridge.fx.api.QuoteTotalsApi;
import com.academy.paybridge.shared.config.AppProperties;
import com.academy.paybridge.shared.exception.ApiException;
import com.academy.paybridge.transfer.api.TransferTotalsApi;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

/** The corridor rule and the dollar limits for payouts. Run at quote time and again at confirm time (R-PAY-13). */
@Service
public class PayoutPolicyService {

    private final CorridorApi corridors;
    private final TransferLimitApi limits;
    private final CustomerApi customers;
    private final TransferTotalsApi transferTotals;
    private final QuoteTotalsApi quoteTotals;
    private final Clock clock;
    private final ZoneId zone;

    public PayoutPolicyService(CorridorApi corridors, TransferLimitApi limits, CustomerApi customers,
                               TransferTotalsApi transferTotals, QuoteTotalsApi quoteTotals, Clock clock,
                               AppProperties appProps) {
        this.corridors = corridors;
        this.limits = limits;
        this.customers = customers;
        this.transferTotals = transferTotals;
        this.quoteTotals = quoteTotals;
        this.clock = clock;
        this.zone = ZoneId.of(appProps.zone() == null ? "Africa/Lagos" : appProps.zone());
    }

    /** R-COR-1 to 4: the sender's stored country, the bank's country and the wallet currency it is paid from. */
    public void checkCorridor(Long customerId, String destinationCountry, String sourceCurrency) {
        CorridorResult result = corridors.check(customers.countryOf(customerId), destinationCountry, sourceCurrency);
        if (!result.allowed()) {
            throw ApiException.unprocessable("CORRIDOR_NOT_ALLOWED",
                    "Payouts from your country to " + destinationCountry + " are not available from a "
                            + sourceCurrency + " wallet. Pay from a wallet in a currency your country allows.");
        }
    }
    /** R-LIM2-2, R-LIM2-3: per-payout and shared daily limit in USD, on the Lagos day. */
    public void checkLimits(Long customerId, String sourceAccount, long usdEquivalentMinor) {
        Instant since = clock.instant().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant();
        long usedToday = transferTotals.usdSentSince(sourceAccount, since) + quoteTotals.payoutUsdSince(customerId, since);

        FraudResult verdict = limits.evaluate(new TransferLimitContext(
                TransferLimitContext.Kind.PAYOUT, usdEquivalentMinor, usedToday));
        if (verdict.decision() == Decision.BLOCK) {
            throw ApiException.unprocessable("LIMIT_EXCEEDED", verdict.joinedReasons());
        }
    }
}