package life.work.IntFit.backend.service.allocation;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class DecimalHoursTest {

    @Test
    void canonicalIsTheShortestRoundTripDecimal() {
        assertThat(DecimalHours.canonical(1.23456).toPlainString()).isEqualTo("1.23456");
        assertThat(DecimalHours.canonical(0.1 + 0.2).toPlainString()).isEqualTo("0.30000000000000004");
        assertThat(DecimalHours.canonical(8.0).toPlainString()).isEqualTo("8");
        assertThat(DecimalHours.canonical(1e-5).toPlainString()).isEqualTo("0.00001");
        assertThat(DecimalHours.canonical(2.5e-13).toPlainString()).isEqualTo("0.00000000000025");
        assertThat(DecimalHours.canonical(-0.0)).isEqualByComparingTo("0");
    }

    @Test
    void everyDoubleFromOneTenTrillionthUpFitsStorageExactly() {
        Random random = new Random(20260917L);
        for (int i = 0; i < 200_000; i++) {
            double exponent = -13 + random.nextDouble() * 19; // 1e-13 .. 1e6
            double value = Math.pow(10, exponent);
            BigDecimal canonical = DecimalHours.canonical(value);
            assertThat(DecimalHours.fitsStorage(canonical)).as("value %s", value).isTrue();
            assertThat(Double.parseDouble(canonical.toPlainString())).isEqualTo(value);
        }
        assertThat(DecimalHours.fitsStorage(DecimalHours.canonical(Math.nextUp(1e-13)))).isTrue();
    }

    @Test
    void storageLimitIsThirtyDecimalPlaces() {
        assertThat(DecimalHours.fitsStorage(new BigDecimal("0.123456789012345678901234567890"))).isTrue();
        assertThat(DecimalHours.fitsStorage(new BigDecimal("0.1234567890123456789012345678901"))).isFalse();
        assertThat(DecimalHours.fitsStorage(new BigDecimal("2.670000000000000000000000000000000"))).isTrue();
        assertThat(DecimalHours.fitsStorage(new BigDecimal("1E+34"))).isTrue();
        assertThat(DecimalHours.fitsStorage(new BigDecimal("1E+35"))).isFalse();
    }

    @Test
    @org.junit.jupiter.api.Timeout(5)
    void extremeExponentsAreHandledWithoutExpansion() {
        BigDecimal huge = new BigDecimal("1E+1000000000");
        BigDecimal tiny = new BigDecimal("1E-1000000000");
        assertThat(DecimalHours.fitsStorage(huge)).isFalse();
        assertThat(DecimalHours.fitsStorage(tiny)).isFalse();
        assertThat(DecimalHours.isExactDouble(huge)).isFalse();
        assertThat(DecimalHours.isExactDouble(tiny)).isFalse();
        assertThat(DecimalHours.display(huge)).isEqualTo("1E+1000000000");
        assertThat(DecimalHours.display(new BigDecimal("2.6700"))).isEqualTo("2.67");
        assertThat(DecimalHours.display(new BigDecimal("1E+1"))).isEqualTo("10");
    }

    @Test
    void exactDoubleDetection() {
        assertThat(DecimalHours.isExactDouble(new BigDecimal("1.23456"))).isTrue();
        assertThat(DecimalHours.isExactDouble(new BigDecimal("1.2345600000"))).isTrue();
        assertThat(DecimalHours.isExactDouble(new BigDecimal("0.30000000000000004"))).isTrue();
        assertThat(DecimalHours.isExactDouble(new BigDecimal("1.23456789012345678901"))).isFalse();
        assertThat(DecimalHours.isExactDouble(new BigDecimal("1E+400"))).isFalse();
        assertThat(DecimalHours.isExactDouble(BigDecimal.ZERO)).isTrue();
    }
}
