package life.work.IntFit.backend.supplier;

import life.work.IntFit.backend.dto.BankCheckDTO;
import life.work.IntFit.backend.model.entity.BankCheck;
import life.work.IntFit.backend.repository.BankCheckRepository;
import life.work.IntFit.backend.supplier.SupplierDtos.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The existing cheque endpoints must not be able to invalidate a recorded supplier payment,
 * while cheques the tracker has never touched keep behaving exactly as before.
 */
@SpringBootTest
@ActiveProfiles("test")
class ChequeWriteGuardTest {

    @Autowired ChequeWriteGuard guard;
    @Autowired SupplierPaymentService service;
    @Autowired BankCheckRepository cheques;
    @Autowired JdbcTemplate jdbc;

    private static BigDecimal n(String s) { return new BigDecimal(s); }

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM supplier_payment_idempotency");
        jdbc.update("DELETE FROM supplier_allocation_change");
        jdbc.update("DELETE FROM supplier_payment_allocation");
        jdbc.update("DELETE FROM supplier_payment");
        jdbc.update("DELETE FROM cheque_movement");
        cheques.deleteAll();
    }

    private BankCheck plainLegacyCheque() {
        BankCheck c = new BankCheck();
        c.setAmount(n("1000"));
        c.setRecipientName("Dulux");
        c.setSerialNumber("L-1");
        c.setCleared(false);
        return cheques.saveAndFlush(c);
    }

    private BankCheck usedCheque() {
        BankCheck c = new BankCheck();
        c.setAmount(n("1000"));
        c.setRecipientName("Al-Etimad");
        c.setSerialNumber("U-1");
        c.setIssuerType(SupplierEnums.IssuerType.CLIENT);
        c.setCurrentHolder(SupplierEnums.Holder.US);
        c = cheques.saveAndFlush(c);
        service.create(new CreatePaymentRequest(UUID.randomUUID().toString(),
                SupplierEnums.Method.CLIENT_CHEQUE, n("1000"), LocalDate.of(2026, 10, 25),
                c.getId(), null, null, null, "t", List.of()));
        return cheques.findById(c.getId()).orElseThrow();
    }

    @Test void untrackedChequesAreUnaffected() {
        BankCheck c = plainLegacyCheque();
        assertFalse(guard.isTrackerManaged(c));

        BankCheckDTO patch = new BankCheckDTO();
        patch.setAmount(n("9999"));
        patch.setSerialNumber("changed");
        assertDoesNotThrow(() -> guard.assertPatchAllowed(c.getId(), patch));
        assertDoesNotThrow(() -> guard.assertDeleteAllowed(c.getId()));
    }

    @Test void amountAndSerialAreFrozenOnceAChequeBacksAPayment() {
        BankCheck c = usedCheque();
        assertTrue(guard.isTrackerManaged(c));

        BankCheckDTO amount = new BankCheckDTO();
        amount.setAmount(n("5000"));
        assertThrows(ChequeWriteGuard.Blocked.class, () -> guard.assertPatchAllowed(c.getId(), amount));

        BankCheckDTO serial = new BankCheckDTO();
        serial.setSerialNumber("tampered");
        assertThrows(ChequeWriteGuard.Blocked.class, () -> guard.assertPatchAllowed(c.getId(), serial));
    }

    @Test void harmlessEditsStillWorkOnATrackedCheque() {
        BankCheck c = usedCheque();
        BankCheckDTO patch = new BankCheckDTO();
        patch.setNotes("photo re-taken");
        patch.setImageUrl("https://example.test/x.jpg");
        patch.setDueDate(LocalDate.of(2027, 1, 1));
        patch.setAmount(c.getAmount()); // unchanged value is fine
        assertDoesNotThrow(() -> guard.assertPatchAllowed(c.getId(), patch));
    }

    @Test void theLegacyClearedFlagStaysFreelyEditable() {
        BankCheck c = usedCheque();
        BankCheckDTO patch = new BankCheckDTO();
        patch.setCleared(true);
        // This tracker does not model clearance, so it imposes no workflow on that field.
        assertDoesNotThrow(() -> guard.assertPatchAllowed(c.getId(), patch));
    }

    @Test void deletingAChequeWithPaymentHistoryIsRefusedEvenAfterReversal() {
        BankCheck c = usedCheque();
        long paymentId = service.list(null, null).get(0).id();
        service.reverse(paymentId, new ReverseRequest("mistake", "t"));

        // The payment is reversed, but the cheque really was handed over once.
        assertThrows(ChequeWriteGuard.Blocked.class, () -> guard.assertDeleteAllowed(c.getId()));
        BankCheckDTO patch = new BankCheckDTO();
        patch.setAmount(n("4242"));
        assertThrows(ChequeWriteGuard.Blocked.class, () -> guard.assertPatchAllowed(c.getId(), patch));
    }

    @Test void aVerifiedButUnusedChequeIsAlsoProtectedFromDeletion() {
        BankCheck c = plainLegacyCheque();
        service.verify(c.getId(), new VerifyChequeRequest(
                SupplierEnums.IssuerType.CLIENT, SupplierEnums.Holder.US, "tester", null));
        assertThrows(ChequeWriteGuard.Blocked.class, () -> guard.assertDeleteAllowed(c.getId()));
    }
}
