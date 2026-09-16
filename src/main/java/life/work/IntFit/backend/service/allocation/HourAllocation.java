package life.work.IntFit.backend.service.allocation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Resolves the hours one assignment row contributes to its worksite.
 *
 * <p>Each category is resolved independently: a custom value when the row has
 * one, otherwise the equal share ({@code 8 / n} for regular hours and
 * {@code overtime / n} for overtime, with {@code n} the member's site count for
 * the day). The two are combined before any rounding.
 *
 * <p>Rows with both categories AUTO must keep using each consumer's original
 * expression so today's numbers stay bit-for-bit identical; callers check
 * {@link #isAuto} and only use the helpers below otherwise.
 *
 * <p>The effective hours are expressed exactly as {@code numerator / n}, so
 * money is computed with a single division and rounded only by the consumer's
 * own policy.
 */
public final class HourAllocation {

    /**
     * For hour quotients that cannot be exact (e.g. x / 3). Far more digits than
     * any input has, so the quotient cannot be pushed across a display
     * rounding boundary, even after summing many rows.
     */
    public static final MathContext QUOTIENT_CONTEXT = new MathContext(80, RoundingMode.HALF_EVEN);

    private static final BigDecimal EIGHT = BigDecimal.valueOf(8);

    private HourAllocation() {
    }

    public static boolean isAuto(BigDecimal regularOverride, BigDecimal overtimeOverride) {
        return regularOverride == null && overtimeOverride == null;
    }

    /** Exact {@code regular * n}. */
    public static BigDecimal regularNumerator(BigDecimal regularOverride, long siteCount) {
        return regularOverride != null
                ? regularOverride.multiply(BigDecimal.valueOf(siteCount))
                : DecimalHours.REGULAR_DAY_HOURS;
    }

    /** Exact {@code overtime * n}; {@code dailyOvertime} is the member's (non-negative) daily total. */
    public static BigDecimal overtimeNumerator(BigDecimal overtimeOverride, double dailyOvertime, long siteCount) {
        return overtimeOverride != null
                ? overtimeOverride.multiply(BigDecimal.valueOf(siteCount))
                : DecimalHours.canonical(dailyOvertime);
    }

    /** Exact numerator of this row's effective hours; effective hours = numerator / siteCount. */
    public static BigDecimal effectiveNumerator(BigDecimal regularOverride, BigDecimal overtimeOverride,
                                                double dailyOvertime, long siteCount) {
        return regularNumerator(regularOverride, siteCount)
                .add(overtimeNumerator(overtimeOverride, dailyOvertime, siteCount));
    }

    /** {@code numerator / siteCount}, for hour totals and display. */
    public static BigDecimal hours(BigDecimal numerator, long siteCount) {
        return numerator.divide(BigDecimal.valueOf(siteCount), QUOTIENT_CONTEXT);
    }

    /** Unrounded double of {@code numerator / siteCount}. */
    public static double quotient(BigDecimal numerator, long siteCount) {
        return hours(numerator, siteCount).doubleValue();
    }

    /** {@code dailyWage * numerator / (8 * siteCount)} with one HALF_UP rounding to {@code scale}. */
    public static BigDecimal siteCost(BigDecimal dailyWage, BigDecimal numerator, long siteCount, int scale) {
        return dailyWage.multiply(numerator)
                .divide(EIGHT.multiply(BigDecimal.valueOf(siteCount)), scale, RoundingMode.HALF_UP);
    }
}
