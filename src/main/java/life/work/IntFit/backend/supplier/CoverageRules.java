package life.work.IntFit.backend.supplier;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What a recorded payment covers.
 *
 * Every cheque handed to AL-Eatimad is treated as guaranteed, so it counts exactly like cash
 * the moment it is handed over. Bank clearance, bounced cheques and the follow-up they need
 * are handled outside the app, so nothing here depends on a cheque's later state: not its due
 * date, not the legacy cleared flag, and not who holds it afterwards.
 *
 * A payment therefore counts in full unless it was reversed as a mistaken entry.
 */
public final class CoverageRules {
    private CoverageRules() { }

    /** A payment counts unless it was reversed. Nothing else switches it off. */
    public static boolean isEffective(SupplierPayment p) {
        return p != null && !p.isReversed();
    }

    // ---------------- per-worksite balances ----------------

    /** What still has to be covered for this worksite. */
    public static BigDecimal remainingToCover(BigDecimal supplies, BigDecimal paid) {
        return floorZero(money(supplies).subtract(money(paid)));
    }

    /** Paid beyond what this worksite owes. */
    public static BigDecimal credit(BigDecimal supplies, BigDecimal paid) {
        return floorZero(money(paid).subtract(money(supplies)));
    }

    public static BigDecimal floorZero(BigDecimal v) {
        BigDecimal x = v == null ? BigDecimal.ZERO : v;
        return (x.signum() < 0 ? BigDecimal.ZERO : x).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal money(BigDecimal v) {
        return (v == null ? BigDecimal.ZERO : v).setScale(2, RoundingMode.HALF_UP);
    }
}
