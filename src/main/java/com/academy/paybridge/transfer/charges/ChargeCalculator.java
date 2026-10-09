package com.academy.paybridge.transfer.charges;

import com.academy.paybridge.transfer.api.TransferType;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Works out the charges for a transfer. Numbers in, numbers out: no database, easy to test.
 *
 * Rules:
 *  - Fee: only on payouts to other banks (PayBridge owns both sides of an internal transfer).
 *  - VAT: a percentage of the FEE, never of the amount sent. Rounded half up to the nearest kobo.
 *  - Stamp duty: a flat amount when the transfer is at or above the threshold, internal or external.
 */
@Component
public class ChargeCalculator {

    private final ChargeProperties props;

    public ChargeCalculator(ChargeProperties props) {
        this.props = props;
    }

    public Charges calculate(long amountKobo, TransferType type) {
        long fee = type == TransferType.EXTERNAL ? feeFor(amountKobo) : 0L;
        long vat = BigDecimal.valueOf(fee).multiply(props.vatRate())
                .setScale(0, RoundingMode.HALF_UP).longValueExact();
        long stamp = amountKobo >= props.stampDutyThresholdKobo() ? props.stampDutyKobo() : 0L;
        return new Charges(fee, vat, stamp);
    }

    private long feeFor(long amountKobo) {
        for (ChargeProperties.Band band : props.feeBands()) {
            if (band.upToKobo() == null || amountKobo <= band.upToKobo()) {
                return band.feeKobo();
            }
        }
        throw new IllegalStateException("No fee band matched. The last band must have no up-to-kobo.");
    }
}
