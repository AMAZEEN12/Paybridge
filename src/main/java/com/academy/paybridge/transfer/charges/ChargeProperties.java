package com.academy.paybridge.transfer.charges;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.List;

/** Fee bands, VAT and stamp duty. They live in application.yml because rates change. All money is in kobo. */
@ConfigurationProperties(prefix = "paybridge.charges")
public record ChargeProperties(
        BigDecimal vatRate,
        long stampDutyKobo,
        long stampDutyThresholdKobo,
        List<Band> feeBands) {

    /** upToKobo is null for the last band, meaning "everything above". */
    public record Band(Long upToKobo, long feeKobo) {
    }
}
