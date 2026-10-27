package life.work.IntFit.backend.supplier;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The balance rules in isolation. A handed-over cheque is a payment like cash, so only a
 * reversal can stop one counting.
 */
class CoverageRulesTest {

    private static BigDecimal n(String s) { return new BigDecimal(s); }

    private static SupplierPayment payment(SupplierEnums.Method m) {
        return SupplierPayment.builder().id(1L).method(m).amount(n("100"))
                .paymentDate(LocalDate.now()).build();
    }

    @Test void everyMethodCountsOnceRecorded() {
        for (SupplierEnums.Method m : SupplierEnums.Method.values()) {
            assertTrue(CoverageRules.isEffective(payment(m)), m + " should count immediately");
        }
    }

    @Test void onlyAReversalStopsAPaymentCounting() {
        var p = payment(SupplierEnums.Method.CLIENT_CHEQUE);
        assertTrue(CoverageRules.isEffective(p));
        p.setReversedAt(Instant.now());
        assertFalse(CoverageRules.isEffective(p));
    }

    @Test void chequeHandedOverReducesTheRemainingAmountImmediately() {
        // Supplies 20,000 with a 5,000 cheque handed over and allocated here.
        assertEquals(0, CoverageRules.remainingToCover(n("20000"), n("5000")).compareTo(n("15000.00")));
        assertEquals(0, CoverageRules.credit(n("20000"), n("5000")).compareTo(n("0.00")));
    }

    @Test void payingBeyondSuppliesBecomesCredit() {
        assertEquals(0, CoverageRules.remainingToCover(n("4000"), n("6000")).compareTo(n("0.00")));
        assertEquals(0, CoverageRules.credit(n("4000"), n("6000")).compareTo(n("2000.00")));
    }

    @Test void remainingNeverGoesNegative() {
        assertEquals(0, CoverageRules.remainingToCover(n("1000"), n("2500")).compareTo(n("0.00")));
    }

    @Test void nullsAreTreatedAsZero() {
        assertEquals(0, CoverageRules.remainingToCover(null, null).compareTo(n("0.00")));
        assertEquals(0, CoverageRules.credit(n("500"), null).compareTo(n("0.00")));
    }
}
