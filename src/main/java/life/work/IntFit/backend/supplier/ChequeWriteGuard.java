package life.work.IntFit.backend.supplier;

import life.work.IntFit.backend.dto.BankCheckDTO;
import life.work.IntFit.backend.model.entity.BankCheck;
import life.work.IntFit.backend.repository.BankCheckRepository;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Keeps the existing cheque endpoints from quietly breaking tracker invariants.
 *
 * The old create/patch/delete routes accept a whole DTO and validate only amount, dueDate
 * and recipientName, so without this a cheque's face value could be edited after a payment
 * recorded that value, or a cheque needed by payment history could be deleted.
 *
 * Clearance is deliberately NOT guarded: this tracker does not model it, so the legacy
 * `cleared` field keeps working exactly as it always has.
 *
 * A cheque is "tracker-managed" once it has been verified OR has any payment history at all,
 * active or reversed. Reversed history counts: the cheque was really handed over once, and
 * rewriting its amount afterwards would falsify that record.
 *
 * Everything not listed as protected stays editable, so ordinary use of the existing screens
 * is unaffected. Cheques the tracker has never touched behave exactly as before.
 */
@Component
public class ChequeWriteGuard {

    private final SupplierPaymentRepository payments;
    private final BankCheckRepository cheques;

    public ChequeWriteGuard(SupplierPaymentRepository payments, BankCheckRepository cheques) {
        this.payments = payments;
        this.cheques = cheques;
    }

    public static class Blocked extends RuntimeException {
        public Blocked(String message) { super(message); }
    }

    public boolean isTrackerManaged(BankCheck c) {
        if (c == null || c.getId() == null) return false;
        return c.getAvailabilityVerifiedAt() != null
                || c.getIssuerType() != null
                || c.getCurrentHolder() != null
                || payments.existsByBankCheckId(c.getId());
    }

    public boolean hasActivePayment(Long chequeId) {
        return chequeId != null && payments.findByActiveBankCheckId(chequeId).isPresent();
    }

    /**
     * Identity and money are frozen on a tracker-managed cheque, because a recorded payment
     * carries the cheque's face value and must keep agreeing with it.
     *
     * Everything else stays editable, including the legacy `cleared` flag and dueDate: the
     * tracker neither reads nor writes them, and no payment balance depends on them.
     */
    public void assertPatchAllowed(Long id, BankCheckDTO patch) {
        BankCheck current = cheques.findById(id).orElse(null);
        if (current == null || patch == null || !isTrackerManaged(current)) return;

        if (patch.getAmount() != null && !eq(patch.getAmount(), current.getAmount())) {
            throw new Blocked("This cheque backs a supplier payment, so its amount cannot be changed. "
                    + "Reverse the payment first, or cancel and replace the cheque.");
        }
        if (patch.getSerialNumber() != null
                && !Objects.equals(patch.getSerialNumber(), current.getSerialNumber())) {
            throw new Blocked("This cheque backs a supplier payment, so its serial number cannot be changed.");
        }
    }

    /** History must survive. A cheque the tracker knows about is never deleted. */
    public void assertDeleteAllowed(Long id) {
        BankCheck current = cheques.findById(id).orElse(null);
        if (current == null) return;
        if (payments.existsByBankCheckId(id)) {
            throw new Blocked("This cheque has supplier-payment history and cannot be deleted. "
                    + "Reverse the payment instead, or cancel the cheque.");
        }
        if (isTrackerManaged(current)) {
            throw new Blocked("This cheque has been verified for supplier payments and cannot be deleted. "
                    + "Cancel it instead so the record is kept.");
        }
    }

    private static boolean eq(BigDecimal a, BigDecimal b) {
        return a != null && b != null && a.compareTo(b) == 0;
    }
}
