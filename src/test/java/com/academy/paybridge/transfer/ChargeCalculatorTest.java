package com.academy.paybridge.transfer;

import com.academy.paybridge.transfer.api.TransferType;
import com.academy.paybridge.transfer.charges.ChargeCalculator;
import com.academy.paybridge.transfer.charges.ChargeProperties;
import com.academy.paybridge.transfer.charges.Charges;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChargeCalculatorTest {

    /** The 2026 bands: free up to N5,000, N10 up to N50,000, N50 above. */
    private final ChargeCalculator calc2026 = new ChargeCalculator(new ChargeProperties(
            new BigDecimal("0.075"), 5000, 1_000_000,
            List.of(new ChargeProperties.Band(500_000L, 0), new ChargeProperties.Band(5_000_000L, 1000),
                    new ChargeProperties.Band(null, 5000))));

    /** The 2020 bands: N10 up to N5,000, N25 up to N50,000, N50 above. */
    private final ChargeCalculator calc2020 = new ChargeCalculator(new ChargeProperties(
            new BigDecimal("0.075"), 5000, 1_000_000,
            List.of(new ChargeProperties.Band(500_000L, 1000), new ChargeProperties.Band(5_000_000L, 2500),
                    new ChargeProperties.Band(null, 5000))));

    @Test
    void stampDutyStartsExactlyAtTenThousandNaira() {
        assertThat(calc2026.calculate(999_999, TransferType.INTERNAL).stampDutyKobo()).isZero();
        assertThat(calc2026.calculate(1_000_000, TransferType.INTERNAL).stampDutyKobo()).isEqualTo(5000);
    }

    @Test
    void internalTransfersHaveNoFeeOrVat() {
        Charges c = calc2026.calculate(2_000_000, TransferType.INTERNAL);
        assertThat(c.feeKobo()).isZero();
        assertThat(c.vatKobo()).isZero();
        assertThat(c.stampDutyKobo()).isEqualTo(5000);
        assertThat(c.totalKobo()).isEqualTo(5000);
    }

    @Test
    void externalFeeBands2026() {
        assertThat(calc2026.calculate(500_000, TransferType.EXTERNAL).feeKobo()).isZero();          // N5,000 is free
        assertThat(calc2026.calculate(500_001, TransferType.EXTERNAL).feeKobo()).isEqualTo(1000);    // just above: N10
        assertThat(calc2026.calculate(5_000_000, TransferType.EXTERNAL).feeKobo()).isEqualTo(1000);  // N50,000: N10
        assertThat(calc2026.calculate(5_000_001, TransferType.EXTERNAL).feeKobo()).isEqualTo(5000);  // above: N50
    }

    @Test
    void externalFeeBands2020() {
        assertThat(calc2020.calculate(500_000, TransferType.EXTERNAL).feeKobo()).isEqualTo(1000);
        assertThat(calc2020.calculate(500_001, TransferType.EXTERNAL).feeKobo()).isEqualTo(2500);
        assertThat(calc2020.calculate(5_000_001, TransferType.EXTERNAL).feeKobo()).isEqualTo(5000);
    }

    @Test
    void vatIsOnTheFeeNotOnTheAmountAndRoundsHalfUp() {
        Charges tenNairaFee = calc2026.calculate(2_000_000, TransferType.EXTERNAL);   // N20,000: fee N10
        assertThat(tenNairaFee.feeKobo()).isEqualTo(1000);
        assertThat(tenNairaFee.vatKobo()).isEqualTo(75);                              // 7.5% of N10 = N0.75
        assertThat(tenNairaFee.totalKobo()).isEqualTo(1000 + 75 + 5000);

        Charges twentyFiveNairaFee = calc2020.calculate(2_000_000, TransferType.EXTERNAL);   // fee N25
        assertThat(twentyFiveNairaFee.vatKobo()).isEqualTo(188);                              // 187.5 rounds half up
    }
}
