package life.work.IntFit.backend.supplier;

import life.work.IntFit.backend.model.entity.BankCheck;
import life.work.IntFit.backend.repository.BankCheckRepository;
import life.work.IntFit.backend.supplier.SupplierDtos.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end behaviour of the tracker against the isolated in-memory test database
 * (profile "test": H2, create-drop). Nothing here touches production.
 */
@SpringBootTest
@ActiveProfiles("test")
class SupplierPaymentServiceTest {

    @Autowired SupplierPaymentService service;
    @Autowired BankCheckRepository cheques;
    @Autowired SupplierPaymentRepository payments;
    @Autowired JdbcTemplate jdbc;

    private static final long SITE_A = 101L;
    private static final long SITE_B = 202L;

    private static BigDecimal n(String s) { return new BigDecimal(s); }
    private static String key() { return UUID.randomUUID().toString(); }

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM supplier_payment_idempotency");
        jdbc.update("DELETE FROM supplier_allocation_change");
        jdbc.update("DELETE FROM supplier_payment_allocation");
        jdbc.update("DELETE FROM supplier_payment");
        jdbc.update("DELETE FROM cheque_movement");
        cheques.deleteAll();

        jdbc.update("DELETE FROM ar_payments");
        jdbc.update("DELETE FROM invoice");
        jdbc.update("DELETE FROM worksites");
        jdbc.update("DELETE FROM master_worksites");

        // Two masters, each with one sub-worksite, plus an unmastered catch-all.
        jdbc.update("INSERT INTO master_worksites(id, approved_name) VALUES (?,?)", SITE_A, "Site A");
        jdbc.update("INSERT INTO master_worksites(id, approved_name) VALUES (?,?)", SITE_B, "Site B");
        jdbc.update("INSERT INTO worksites(id, name, is_archived, master_worksite_id) VALUES (1,'A-sub',FALSE,?)", SITE_A);
        jdbc.update("INSERT INTO worksites(id, name, is_archived, master_worksite_id) VALUES (2,'B-sub',FALSE,?)", SITE_B);
        jdbc.update("INSERT INTO worksites(id, name, is_archived, master_worksite_id) VALUES (3,'other',FALSE,NULL)");

        // All-time supplies: A 10,000 and B 4,000, reached through the sub-worksites.
        invoice(1, 1, "10000", "2024-01-15");
        invoice(2, 2, "4000", "2026-10-20");
        // Supplies that cannot reach a master.
        invoice(3, 3, "500", "2026-10-21");

        // An existing client receipt for A. The tracker must display it and never add to it.
        jdbc.update("INSERT INTO ar_payments(id, master_worksite_id, amount, payment_date, created_at) "
                + "VALUES (1,?,?,?,?)", SITE_A, n("8000"), LocalDate.of(2026, 10, 1), java.time.Instant.now());
    }

    private void invoice(long id, long worksiteId, String total, String date) {
        jdbc.update("INSERT INTO invoice(id, worksite_id, total, net_total, date) VALUES (?,?,?,?,?)",
                id, worksiteId, new BigDecimal(total), new BigDecimal(total),
                java.sql.Timestamp.valueOf(date + " 09:00:00"));
    }

    private BankCheck cheque(String amount, SupplierEnums.IssuerType issuer, SupplierEnums.Holder holder) {
        BankCheck c = new BankCheck();
        c.setAmount(new BigDecimal(amount));
        c.setDueDate(LocalDate.of(2026, 12, 1));
        c.setRecipientName("Al-Etimad");
        c.setSerialNumber("S-" + UUID.randomUUID().toString().substring(0, 6));
        c.setIssuerType(issuer);
        c.setCurrentHolder(holder);
        return cheques.saveAndFlush(c);
    }

    /** A pre-existing row: every tracker column still NULL. */
    private BankCheck legacyCheque(String amount) {
        BankCheck c = new BankCheck();
        c.setAmount(new BigDecimal(amount));
        c.setRecipientName("Al-Etimad");
        c.setFromWhom("Personal");
        c.setCleared(false);
        return cheques.saveAndFlush(c);
    }

    /** A legacy row that the old screens had already marked cleared. */
    private BankCheck legacyClearedCheque(String amount) {
        BankCheck c = legacyCheque(amount);
        c.setCleared(true);
        return cheques.saveAndFlush(c);
    }

    private WorksiteRow rowFor(long masterId) {
        return service.outstanding().stream().filter(r -> r.masterWorksiteId() == masterId).findFirst()
                .orElseGet(() -> service.browse(null, 0, 100, LocalDate.of(2026, 10, 27)).rows().stream()
                        .filter(r -> r.masterWorksiteId() == masterId).findFirst().orElseThrow());
    }

    private long arPaymentCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM ar_payments", Long.class);
    }

    // =====================================================================

    @Test void ledgerStartsEmptyWhileAllSuppliesCount() {
        var row = rowFor(SITE_A);
        assertEquals(0, row.supplies().compareTo(n("10000.00")), "supplies include a 2024 invoice");
        assertEquals(0, row.paid().compareTo(n("0.00")));
        assertEquals(0, row.remainingToCover().compareTo(n("10000.00")));
        // Receipts are displayed, never used to offset supplies.
        assertEquals(0, row.receipts().compareTo(n("8000.00")));
    }

    @Test void cashPaymentSettlesImmediatelyAndLeavesARUntouched() {
        long before = arPaymentCount();
        var p = service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.CASH, n("6000"),
                LocalDate.of(2026, 10, 25), null, null, "cash-1", null, "tester",
                List.of(new AllocationInput(SITE_A, n("5000")), new AllocationInput(SITE_B, n("1000")))));

        assertTrue(p.effective());
        assertEquals(0, p.unallocated().compareTo(n("0.00")));
        assertEquals(0, rowFor(SITE_A).remainingToCover().compareTo(n("5000.00")));
        assertEquals(0, rowFor(SITE_B).remainingToCover().compareTo(n("3000.00")));
        assertEquals(before, arPaymentCount(), "a supplier payment never creates a client receipt");
    }

    @Test void clientChequeFromSiteACoversSiteBWithoutASecondReceipt() {
        long before = arPaymentCount();
        var c = cheque("8000", SupplierEnums.IssuerType.CLIENT, SupplierEnums.Holder.US);

        var p = service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.CLIENT_CHEQUE, n("8000"),
                LocalDate.of(2026, 10, 25), c.getId(), LocalDate.of(2026, 10, 25), null, null, "tester",
                List.of(new AllocationInput(SITE_B, n("6000")))));

        assertTrue(p.effective());
        assertEquals(0, p.unallocated().compareTo(n("2000.00")), "2,000 of the cheque is not yet assigned");

        var b = rowFor(SITE_B);
        // Handed over means paid, immediately: 6,000 against 4,000 of supplies.
        assertEquals(0, b.paid().compareTo(n("6000.00")));
        assertEquals(0, b.remainingToCover().compareTo(n("0.00")));
        assertEquals(0, b.credit().compareTo(n("2000.00")));

        // Site A keeps its own supplies and its single original receipt.
        assertEquals(0, rowFor(SITE_A).remainingToCover().compareTo(n("10000.00")));
        assertEquals(before, arPaymentCount());

        // Custody: handing it on is recorded as a real movement.
        var moves = service.movementsOf(c.getId());
        assertEquals(1, moves.size());
        assertEquals(SupplierEnums.Holder.SUPPLIER, moves.get(0).getToHolder());
    }

    @Test void dueDateAndLegacyClearedDoNotChangeTheRecordedAmount() {
        var c = cheque("4000", SupplierEnums.IssuerType.CLIENT, SupplierEnums.Holder.US);
        c.setDueDate(LocalDate.of(2027, 6, 1)); // far in the future
        cheques.saveAndFlush(c);

        service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.CLIENT_CHEQUE, n("4000"),
                LocalDate.of(2026, 10, 25), c.getId(), null, null, null, "tester",
                List.of(new AllocationInput(SITE_B, n("4000")))));
        assertEquals(0, rowFor(SITE_B).paid().compareTo(n("4000.00")), "post-dated still pays now");

        // The legacy flag is other people's workflow; the tracker ignores it either way.
        var stored = cheques.findById(c.getId()).orElseThrow();
        stored.setCleared(true);
        cheques.saveAndFlush(stored);
        assertEquals(0, rowFor(SITE_B).paid().compareTo(n("4000.00")));

        stored = cheques.findById(c.getId()).orElseThrow();
        stored.setCleared(false);
        cheques.saveAndFlush(stored);
        assertEquals(0, rowFor(SITE_B).paid().compareTo(n("4000.00")));
    }

    @Test void laterCustodyChangesDoNotSilentlyMoveBalances() {
        var c = cheque("4000", SupplierEnums.IssuerType.CLIENT, SupplierEnums.Holder.US);
        service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.CLIENT_CHEQUE, n("4000"),
                LocalDate.of(2026, 10, 25), c.getId(), null, null, null, "tester",
                List.of(new AllocationInput(SITE_B, n("4000")))));
        assertEquals(0, rowFor(SITE_B).paid().compareTo(n("4000.00")));

        // Recording that the paper came back is history, not a refund. Only an explicit
        // reversal changes what was paid.
        service.recordMovement(c.getId(), new MovementRequest(SupplierEnums.Holder.SUPPLIER,
                SupplierEnums.Holder.US, "AL-Eatimad", LocalDate.of(2026, 10, 28), "collected", "tester"));

        assertEquals(0, rowFor(SITE_B).paid().compareTo(n("4000.00")), "balances do not follow custody");
        assertEquals(2, service.movementsOf(c.getId()).size(), "both movements are kept");
    }

    @Test void reversingATrackerEntryKeepsHistoryAndDoesNotClaimWeHoldTheCheque() {
        var c = cheque("4000", SupplierEnums.IssuerType.CLIENT, SupplierEnums.Holder.US);
        var p = service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.CLIENT_CHEQUE, n("4000"),
                LocalDate.of(2026, 10, 25), c.getId(), null, null, null, "tester",
                List.of(new AllocationInput(SITE_B, n("4000")))));

        var reversed = service.reverse(p.id(), new ReverseRequest("entered by mistake", "tester"));
        assertTrue(reversed.reversed());
        assertFalse(reversed.effective());
        assertEquals(0, rowFor(SITE_B).remainingToCover().compareTo(n("4000.00")));

        // The historical link survives; only the active marker is released.
        var stored = payments.findById(p.id()).orElseThrow();
        assertEquals(c.getId(), stored.getBankCheckId(), "history is never erased");
        assertNull(stored.getActiveBankCheckId());
        // Custody still says the supplier has it, so it is not offered for a new handover.
        assertEquals(SupplierEnums.Holder.SUPPLIER,
                cheques.findById(c.getId()).orElseThrow().getCurrentHolder());
        assertTrue(service.availableForHandover(null).stream().noneMatch(o -> o.id() == c.getId()));
    }

    @Test void aChequeTheSupplierHoldsCannotBeHandedOverAgain() {
        var c = cheque("4000", SupplierEnums.IssuerType.CLIENT, SupplierEnums.Holder.US);
        service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.CLIENT_CHEQUE, n("4000"),
                LocalDate.of(2026, 10, 25), c.getId(), null, null, null, "t", List.of()));
        var p2 = new CreatePaymentRequest(key(), SupplierEnums.Method.CLIENT_CHEQUE, n("4000"),
                LocalDate.of(2026, 10, 26), c.getId(), null, null, null, "t", List.of());
        var ex = assertThrows(SupplierPaymentService.Problem.class, () -> service.create(p2));
        assertEquals(409, ex.status);
    }

    @Test void legacyChequeIsUnusableUntilVerifiedAndVerifyingAloneCreatesNothing() {
        var legacy = legacyCheque("5000");
        assertNull(legacy.getIssuerType(), "a legacy row says nothing about its issuer");
        assertNull(legacy.getCurrentHolder(), "or about where it is");

        var req = new CreatePaymentRequest(key(), SupplierEnums.Method.OWN_CHEQUE, n("5000"),
                LocalDate.of(2026, 10, 25), legacy.getId(), null, null, null, "t", List.of());
        var ex = assertThrows(SupplierPaymentService.Problem.class, () -> service.create(req));
        assertEquals(409, ex.status);

        var opt = service.verify(legacy.getId(), new VerifyChequeRequest(
                SupplierEnums.IssuerType.OWN, SupplierEnums.Holder.US, "tester", null));
        assertFalse(opt.needsVerification());
        // Confirming availability creates no payment and moves no balance.
        assertEquals(0, payments.count());
        assertTrue(service.movementsOf(legacy.getId()).isEmpty(), "no receipt date is invented");
    }

    @Test void ownChequeReducesTheRemainingAmountOnHandover() {
        long before = arPaymentCount();
        var c = cheque("12000", SupplierEnums.IssuerType.OWN, SupplierEnums.Holder.US);

        var p = service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.OWN_CHEQUE, n("12000"),
                LocalDate.of(2026, 10, 25), c.getId(), null, null, null, "t",
                List.of(new AllocationInput(SITE_A, n("7000")))));

        assertTrue(p.effective());
        assertEquals(0, rowFor(SITE_A).paid().compareTo(n("7000.00")));
        assertEquals(0, rowFor(SITE_A).remainingToCover().compareTo(n("3000.00")));
        // The rest of the cheque is simply not assigned yet.
        assertEquals(0, p.unallocated().compareTo(n("5000.00")));
        // Writing our own cheque never creates a client receipt.
        assertEquals(before, arPaymentCount());
    }

    @Test void chequePaymentMustEqualFaceValue() {
        var c = cheque("8000", SupplierEnums.IssuerType.CLIENT, SupplierEnums.Holder.US);
        var req = new CreatePaymentRequest(key(), SupplierEnums.Method.CLIENT_CHEQUE, n("6000"),
                LocalDate.of(2026, 10, 25), c.getId(), null, null, null, "t", List.of());
        var ex = assertThrows(SupplierPaymentService.Problem.class, () -> service.create(req));
        assertEquals(400, ex.status);
    }

    @Test void overAllocationIsRejectedAtCreationAndOnLaterChanges() {
        var tooMuch = new CreatePaymentRequest(key(), SupplierEnums.Method.CASH, n("1000"),
                LocalDate.of(2026, 10, 25), null, null, null, null, "t",
                List.of(new AllocationInput(SITE_A, n("600")), new AllocationInput(SITE_B, n("600"))));
        assertEquals(400, assertThrows(SupplierPaymentService.Problem.class,
                () -> service.create(tooMuch)).status);

        var p = service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.CASH, n("1000"),
                LocalDate.of(2026, 10, 25), null, null, null, null, "t",
                List.of(new AllocationInput(SITE_A, n("600")))));
        assertEquals(400, assertThrows(SupplierPaymentService.Problem.class,
                () -> service.changeAllocation(p.id(),
                        new AllocationChangeRequest(SITE_B, n("500"), "too much", "t"))).status);
    }

    @Test void unallocatedMoneyCanBeAssignedLaterWithHistory() {
        var p = service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.BANK, n("5000"),
                LocalDate.of(2026, 10, 25), null, null, null, null, "t",
                List.of(new AllocationInput(SITE_A, n("2000")))));
        assertEquals(0, p.unallocated().compareTo(n("3000.00")));

        var after = service.changeAllocation(p.id(),
                new AllocationChangeRequest(SITE_B, n("3000"), "assign the rest", "t"));
        assertEquals(0, after.unallocated().compareTo(n("0.00")));
        assertEquals(0, rowFor(SITE_B).remainingToCover().compareTo(n("1000.00")));

        // Reassign A down to 1,000.
        service.changeAllocation(p.id(), new AllocationChangeRequest(SITE_A, n("1000"), "reassign", "t"));
        var history = service.historyOf(p.id());
        assertEquals(3, history.size(), "create, add, change are all recorded");
        assertEquals(0, history.get(2).getOldAmount().compareTo(n("2000.00")));
        assertEquals(0, history.get(2).getNewAmount().compareTo(n("1000.00")));
    }

    @Test void overpayingLeavesACreditThatStaysVisible() {
        var c = cheque("6000", SupplierEnums.IssuerType.CLIENT, SupplierEnums.Holder.US);
        service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.CLIENT_CHEQUE, n("6000"),
                LocalDate.of(2026, 10, 25), c.getId(), null, null, null, "t",
                List.of(new AllocationInput(SITE_B, n("6000")))));

        var b = service.outstanding().stream()
                .filter(r -> r.masterWorksiteId() == SITE_B).findFirst().orElse(null);
        assertNotNull(b, "a project in credit must stay reachable");
        assertEquals(0, b.remainingToCover().compareTo(n("0.00")));
        assertEquals(0, b.credit().compareTo(n("2000.00")));
    }

    @Test void sameKeyReturnsTheOriginalAndADifferentBodyIsRejected() {
        String k = key();
        var req = new CreatePaymentRequest(k, SupplierEnums.Method.CASH, n("1000"),
                LocalDate.of(2026, 10, 25), null, null, "ref", null, "t",
                List.of(new AllocationInput(SITE_A, n("1000"))));
        var first = service.create(req);
        var retry = service.create(req);
        assertEquals(first.id(), retry.id(), "a retry must not create a second payment");
        assertEquals(1, payments.count());

        var different = new CreatePaymentRequest(k, SupplierEnums.Method.CASH, n("2000"),
                LocalDate.of(2026, 10, 25), null, null, "ref", null, "t", List.of());
        assertEquals(409, assertThrows(SupplierPaymentService.Problem.class,
                () -> service.create(different)).status);
    }

    @Test void twoIdenticalCashPaymentsOnOneDayAreBothKept() {
        var a = service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.CASH, n("5000"),
                LocalDate.of(2026, 10, 25), null, null, "same", null, "t", List.of()));
        var b = service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.CASH, n("5000"),
                LocalDate.of(2026, 10, 25), null, null, "same", null, "t", List.of()));
        assertNotEquals(a.id(), b.id(), "identical values are not a duplicate; only the key is");
        assertEquals(2, payments.count());
    }

    @Test void concurrentHandoversOfOneChequeProduceExactlyOnePayment() throws Exception {
        var c = cheque("4000", SupplierEnums.IssuerType.CLIENT, SupplierEnums.Holder.US);
        int threads = 4;
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<Object>> fs = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                fs.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try {
                        return service.create(new CreatePaymentRequest(key(),
                                SupplierEnums.Method.CLIENT_CHEQUE, n("4000"),
                                LocalDate.of(2026, 10, 25), c.getId(), null, null, null, "t", List.of()));
                    } catch (RuntimeException e) {
                        return e;
                    }
                }));
            }
            start.countDown();
            long ok = 0;
            for (var f : fs) if (f.get(20, TimeUnit.SECONDS) instanceof PaymentView) ok++;
            assertEquals(1, ok, "exactly one handover wins");
            assertEquals(1, payments.count());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test void unassignedSuppliesAreVisibleAndExcludedFromMasterRows() {
        var view = service.unassignedSupplies();
        assertEquals(0, view.total().compareTo(n("500.00")));
        assertFalse(view.byWorksite().isEmpty());
        // The 500 is not folded into either master.
        assertEquals(0, rowFor(SITE_A).supplies().compareTo(n("10000.00")));
        assertEquals(0, rowFor(SITE_B).supplies().compareTo(n("4000.00")));
    }

    @Test void aLegacyClearedChequeBehavesLikeAnyOtherOnceConfirmed() {
        var legacy = legacyClearedCheque("3000");
        assertTrue(legacy.isCleared(), "legacy value is preserved, not rewritten");

        service.verify(legacy.getId(), new VerifyChequeRequest(
                SupplierEnums.IssuerType.CLIENT, SupplierEnums.Holder.US, "tester", null));
        service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.CLIENT_CHEQUE, n("3000"),
                LocalDate.of(2026, 10, 25), legacy.getId(), null, null, null, "t",
                List.of(new AllocationInput(SITE_B, n("3000")))));

        assertEquals(0, rowFor(SITE_B).paid().compareTo(n("3000.00")));
        assertTrue(cheques.findById(legacy.getId()).orElseThrow().isCleared(),
                "the tracker never touches the legacy flag");
    }

    @Test void anExistingProjectWithNoSuppliesMayStillReceiveMoneyAndGoIntoCredit() {
        // SITE_C exists but has no invoices at all.
        jdbc.update("INSERT INTO master_worksites(id, approved_name) VALUES (303,'Site C')");

        var p = service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.CASH, n("1500"),
                LocalDate.of(2026, 10, 25), null, null, null, null, "t",
                List.of(new AllocationInput(303L, n("1500")))));
        assertTrue(p.effective());

        var row = service.outstanding().stream()
                .filter(r -> r.masterWorksiteId() == 303L).findFirst().orElseThrow();
        assertEquals(0, row.supplies().compareTo(n("0.00")));
        assertEquals(0, row.paid().compareTo(n("1500.00")));
        assertEquals(0, row.remainingToCover().compareTo(n("0.00")));
        assertEquals(0, row.credit().compareTo(n("1500.00")), "paying a project with no supplies is credit");
    }

    @Test void anAllocationToAProjectThatDoesNotExistIsRejectedAndNothingIsSaved() {
        long paymentsBefore = payments.count();
        var req = new CreatePaymentRequest(key(), SupplierEnums.Method.CASH, n("1000"),
                LocalDate.of(2026, 10, 25), null, null, null, null, "t",
                List.of(new AllocationInput(999999L, n("1000"))));

        var ex = assertThrows(SupplierPaymentService.Problem.class, () -> service.create(req));
        assertEquals(400, ex.status);
        assertTrue(ex.getMessage().contains("999999"));

        // No payment, no allocation and no spent idempotency key survive the refusal.
        assertEquals(paymentsBefore, payments.count());
        assertEquals(0, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_payment_allocation", Long.class));
        assertEquals(0, (long) jdbc.queryForObject(
                "SELECT COUNT(*) FROM supplier_payment_idempotency", Long.class));
    }

    @Test void oneBadProjectRejectsTheWholeRequestEvenWhenOthersAreValid() {
        var req = new CreatePaymentRequest(key(), SupplierEnums.Method.CASH, n("1000"),
                LocalDate.of(2026, 10, 25), null, null, null, null, "t",
                List.of(new AllocationInput(SITE_A, n("500")), new AllocationInput(888888L, n("500"))));
        assertEquals(400, assertThrows(SupplierPaymentService.Problem.class,
                () -> service.create(req)).status);
        assertEquals(0, payments.count());
        assertEquals(0, rowFor(SITE_A).paid().compareTo(n("0.00")), "the valid half is not saved either");
    }

    @Test void laterAllocationToAMissingProjectIsRejectedButRemovalStillWorks() {
        var p = service.create(new CreatePaymentRequest(key(), SupplierEnums.Method.CASH, n("1000"),
                LocalDate.of(2026, 10, 25), null, null, null, null, "t",
                List.of(new AllocationInput(SITE_A, n("400")))));

        assertEquals(400, assertThrows(SupplierPaymentService.Problem.class,
                () -> service.changeAllocation(p.id(),
                        new AllocationChangeRequest(777777L, n("100"), "typo", "t"))).status);
        assertEquals(0, service.get(p.id()).allocated().compareTo(n("400.00")));

        // Setting an amount to zero removes a line and needs no existence check.
        var after = service.changeAllocation(p.id(),
                new AllocationChangeRequest(SITE_A, n("0"), "undo", "t"));
        assertEquals(0, after.allocated().compareTo(n("0.00")));
    }

    @Test void browsingRanksByLatestActivityAndPagesStably() {
        // Site B has the recent invoice (2026-10-20); Site A's is from 2024.
        var page = service.browse(null, 0, 1, LocalDate.of(2026, 10, 27));
        assertEquals(1, page.rows().size());
        assertEquals(SITE_B, page.rows().get(0).masterWorksiteId());
        assertTrue(page.hasMore());
        assertNotNull(page.snapshotId());

        var page2 = service.browse(page.snapshotId(), page.nextOffset(), 1, null);
        assertEquals(SITE_A, page2.rows().get(0).masterWorksiteId());
        assertFalse(page2.hasMore());
        // Balances on a page are live, not frozen with the order.
        assertEquals(0, page2.rows().get(0).supplies().compareTo(n("10000.00")));
        assertEquals(0, page.unassignedSupplies().compareTo(n("500.00")));
    }

    @Test void expiredOrUnknownSnapshotIsReportedNotSilentlyRebuilt() {
        assertEquals(410, assertThrows(SupplierPaymentService.Problem.class,
                () -> service.browse("no-such-snapshot", 0, 10, null)).status);
    }
}
