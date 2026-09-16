package life.work.IntFit.backend.service.allocation;

import java.math.BigDecimal;

/**
 * Exact decimal handling for allocated hours.
 *
 * <p>Allocation values travel and persist as exact decimals (JSON text parsed
 * straight into {@link BigDecimal}, stored as DECIMAL(65,30)). The daily
 * overtime total, however, is still a DOUBLE column. Its exact decimal value is
 * defined as the shortest decimal that parses back to the stored double, which
 * is what {@link Double#toString(double)} returns on Java 19+ and what
 * JavaScript's {@code String(number)} returns, so both ends agree on it.
 *
 * <p>Supported allocation values have at most 30 decimal places and 35 integer
 * digits once trailing zeros are removed. Any positive double of at least 1e-13
 * has a canonical form with at most 29 decimal places, so it can always be split.
 *
 * <p>Request values are arbitrary {@link BigDecimal}s (e.g. {@code 1E+1000000000});
 * only {@link #fitsStorage}, {@link #isExactDouble} and {@link #display} are safe
 * to call on them before validation.
 */
public final class DecimalHours {

    public static final int STORAGE_PRECISION = 65;
    public static final int STORAGE_SCALE = 30;
    private static final int MAX_INTEGER_DIGITS = STORAGE_PRECISION - STORAGE_SCALE;

    public static final BigDecimal REGULAR_DAY_HOURS = new BigDecimal("8");

    private DecimalHours() {
    }

    /** Shortest decimal that round-trips to {@code value}; relies on the Java 19+ {@code Double.toString} contract. */
    public static BigDecimal canonical(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Not a finite number: " + value);
        }
        if (value == 0d) {
            return BigDecimal.ZERO;
        }
        return normalize(new BigDecimal(Double.toString(value)));
    }

    /**
     * Numerically equal value without trailing zeros and without a negative scale.
     * Only for values that {@link #fitsStorage fit storage} or come from a double.
     */
    public static BigDecimal normalize(BigDecimal value) {
        if (value.signum() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }

    /** Plain string of a stored or validated value. */
    public static String toPlainString(BigDecimal value) {
        return value == null ? null : normalize(value).toPlainString();
    }

    /** Bounded text for messages, whatever the value's size. */
    public static String display(BigDecimal value) {
        return fitsStorage(value) ? normalize(value).toPlainString() : value.stripTrailingZeros().toString();
    }

    /** True when the value can be stored in the allocation columns without MySQL rounding it. */
    public static boolean fitsStorage(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        long decimals = Math.max(stripped.scale(), 0);
        long integerDigits = (long) stripped.precision() - stripped.scale();
        return decimals <= STORAGE_SCALE && integerDigits <= MAX_INTEGER_DIGITS;
    }

    /** Converts an exact decimal to the double a DOUBLE column would hold. */
    public static double toDouble(BigDecimal value) {
        return Double.parseDouble(value.toString());
    }

    /** True when a DOUBLE column stores exactly this decimal (no rounding on the way in). */
    public static boolean isExactDouble(BigDecimal value) {
        double d = toDouble(value);
        return Double.isFinite(d) && canonical(d).compareTo(value) == 0;
    }
}
