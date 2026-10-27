package life.work.IntFit.backend.supplier;

import life.work.IntFit.backend.config.TimeConfig;
import life.work.IntFit.backend.model.entity.BankCheck;
import life.work.IntFit.backend.repository.BankCheckRepository;
import life.work.IntFit.backend.supplier.SupplierDtos.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.stream.Collectors;

/**
 * The AL-Eatimad supplier-payment tracker.
 *
 * The ledger starts empty by design: all existing supplies count, but no historical supplier
 * payment is imported and no opening balance is created, so these totals describe what has
 * been recorded here rather than the supplier's own account balance.
 *
 * Client receipts (ar_payments) are read for display only. Handing a client cheque to the
 * supplier records a supplier payment and a custody movement, never a second receipt, and
 * never changes the supplies figure.
 */
@Service
public class SupplierPaymentService {

    private static final ZoneId ZONE = TimeConfig.BUSINESS_ZONE;

    private final SupplierPaymentRepository payments;
    private final SupplierPaymentAllocationRepository allocations;
    private final ChequeMovementRepository movements;
    private final AllocationChangeRepository changes;
    private final PaymentIdempotencyRepository idempotency;
    private final BankCheckRepository cheques;
    private final SupplierAggregateDao agg;
    private final WorksiteBrowseSnapshots snapshots;

    public SupplierPaymentService(SupplierPaymentRepository payments,
                                  SupplierPaymentAllocationRepository allocations,
                                  ChequeMovementRepository movements,
                                  AllocationChangeRepository changes,
                                  PaymentIdempotencyRepository idempotency,
                                  BankCheckRepository cheques,
                                  SupplierAggregateDao agg,
                                  WorksiteBrowseSnapshots snapshots) {
        this.payments = payments;
        this.allocations = allocations;
        this.movements = movements;
        this.changes = changes;
        this.idempotency = idempotency;
        this.cheques = cheques;
        this.agg = agg;
        this.snapshots = snapshots;
    }

    public static class Problem extends RuntimeException {
        public final int status;
        public Problem(int status, String message) { super(message); this.status = status; }
    }

    // =======================================================================
    // Browsing
    // =======================================================================

    /**
     * Master worksites, each appearing once, ranked by their latest qualifying activity.
     *
     * The order is frozen in a snapshot for a browsing session, so paging further back can
     * neither skip nor repeat a worksite while new activity arrives. Asking without a
     * snapshot id rebuilds it.
     */
    @Transactional(readOnly = true)
    public WorksitePage browse(String snapshotId, int offset, int limit, LocalDate today) {
        WorksiteBrowseSnapshots.Snapshot snap = (snapshotId == null || snapshotId.isBlank())
                ? snapshots.create(agg.lastActivityByMaster(), today == null ? LocalDate.now(ZONE) : today)
                : snapshots.get(snapshotId)
                    .orElseThrow(() -> new Problem(410, "That browsing session expired. Refresh the list."));

        int from = Math.max(0, offset);
        int size = Math.min(Math.max(1, limit), 100);
        List<WorksiteBrowseSnapshots.Entry> slice = snap.slice(from, size);

        Map<Long, BigDecimal> supplies = agg.suppliesByMaster();
        Map<Long, BigDecimal> receipts = agg.receiptsByMaster();
        Map<Long, String> names = agg.masterNames();
        Map<Long, BigDecimal> paid = paidByMaster();

        List<WorksiteRow> rows = slice.stream()
                .map(e -> row(e.masterId(), e.lastActivityOn(), e.group(), supplies, receipts, paid, names))
                .toList();

        int next = from + slice.size();
        return new WorksitePage(snap.id(), rows, next < snap.size() ? next : null,
                next < snap.size(), snap.size(), agg.unassignedSuppliesTotal());
    }

    /**
     * Every worksite that still needs attention: something left to cover, or a credit.
     * Inactive worksites are included.
     */
    @Transactional(readOnly = true)
    public List<WorksiteRow> outstanding() {
        Map<Long, BigDecimal> supplies = agg.suppliesByMaster();
        Map<Long, BigDecimal> receipts = agg.receiptsByMaster();
        Map<Long, String> names = agg.masterNames();
        Map<Long, BigDecimal> paid = paidByMaster();
        Map<Long, LocalDate> last = agg.lastActivityByMaster();

        Set<Long> ids = new TreeSet<>();
        ids.addAll(supplies.keySet());
        ids.addAll(paid.keySet());

        return ids.stream()
                .map(id -> row(id, last.get(id), null, supplies, receipts, paid, names))
                .filter(r -> r.remainingToCover().signum() > 0 || r.credit().signum() > 0)
                .sorted(Comparator.comparing(WorksiteRow::remainingToCover).reversed()
                        .thenComparing(WorksiteRow::masterWorksiteId))
                .toList();
    }

    private WorksiteRow row(long id, LocalDate lastOn, String group,
                            Map<Long, BigDecimal> supplies, Map<Long, BigDecimal> receipts,
                            Map<Long, BigDecimal> paidByMaster, Map<Long, String> names) {
        BigDecimal sup = CoverageRules.money(supplies.getOrDefault(id, BigDecimal.ZERO));
        BigDecimal paid = CoverageRules.money(paidByMaster.getOrDefault(id, BigDecimal.ZERO));
        return new WorksiteRow(id, names.getOrDefault(id, "#" + id), lastOn, group,
                sup, CoverageRules.money(receipts.getOrDefault(id, BigDecimal.ZERO)),
                paid,
                CoverageRules.remainingToCover(sup, paid),
                CoverageRules.credit(sup, paid));
    }

    /**
     * masterId to amount paid, from the allocations of every payment that has not been
     * reversed. The cheque's own state is irrelevant: once handed over it is a payment.
     */
    private Map<Long, BigDecimal> paidByMaster() {
        List<SupplierPayment> all = payments.findAll();
        if (all.isEmpty()) return Map.of();
        Map<Long, List<SupplierPaymentAllocation>> byPayment = allocations
                .findByPaymentIdIn(all.stream().map(SupplierPayment::getId).toList())
                .stream().collect(Collectors.groupingBy(SupplierPaymentAllocation::getPaymentId));

        Map<Long, BigDecimal> out = new HashMap<>();
        for (SupplierPayment p : all) {
            if (!CoverageRules.isEffective(p)) continue;
            for (SupplierPaymentAllocation a : byPayment.getOrDefault(p.getId(), List.of())) {
                out.merge(a.getMasterWorksiteId(), a.getAmount(), BigDecimal::add);
            }
        }
        return out;
    }

    // =======================================================================
    // Recording a payment
    // =======================================================================

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentView create(CreatePaymentRequest req) {
        if (req == null) throw new Problem(400, "A request body is required.");
        if (req.idempotencyKey() == null || req.idempotencyKey().isBlank()) {
            throw new Problem(400, "An idempotencyKey is required; reuse the same key when retrying.");
        }
        if (req.method() == null) throw new Problem(400, "method is required.");
        if (req.paymentDate() == null) throw new Problem(400, "paymentDate is required.");
        if (req.amount() == null || req.amount().signum() <= 0) {
            throw new Problem(400, "amount must be greater than zero.");
        }

        String hash = hash(req);
        Optional<PaymentIdempotencyKey> seen = idempotency.findById(req.idempotencyKey());
        if (seen.isPresent()) {
            if (!seen.get().getRequestHash().equals(hash)) {
                throw new Problem(409, "That idempotency key was already used for a different payment.");
            }
            return view(payments.findById(seen.get().getPaymentId())
                    .orElseThrow(() -> new Problem(500, "The original payment is missing.")));
        }

        BankCheck cheque = null;
        boolean chequeRoute = req.method() == SupplierEnums.Method.CLIENT_CHEQUE
                || req.method() == SupplierEnums.Method.OWN_CHEQUE;

        if (chequeRoute) {
            if (req.bankCheckId() == null) throw new Problem(400, "bankCheckId is required for a cheque payment.");
            cheque = cheques.findById(req.bankCheckId())
                    .orElseThrow(() -> new Problem(404, "That cheque does not exist."));
            assertChequeUsable(cheque, req.method());
            // A cheque is handed over whole, so the payment carries its full face value.
            if (cheque.getAmount() == null || cheque.getAmount().compareTo(req.amount()) != 0) {
                throw new Problem(400, "A cheque payment must equal the cheque's face value.");
            }
            if (payments.findByActiveBankCheckId(cheque.getId()).isPresent()) {
                throw new Problem(409, "That cheque already backs an active supplier payment.");
            }
        } else if (req.bankCheckId() != null) {
            throw new Problem(400, "bankCheckId is only valid for a cheque payment.");
        }

        SupplierPayment p = SupplierPayment.builder()
                .paymentDate(req.paymentDate())
                .amount(CoverageRules.money(req.amount()))
                .method(req.method())
                .bankCheckId(req.bankCheckId())
                .reference(req.reference())
                .notes(req.notes())
                .recordedBy(req.recordedBy())
                .build();
        try {
            p = payments.saveAndFlush(p);
        } catch (DataIntegrityViolationException e) {
            // The unique index on active_bank_check_id is the final word under concurrency.
            throw new Problem(409, "That cheque already backs an active supplier payment.");
        }

        applyAllocations(p, req.allocations(), req.recordedBy(), "initial allocation");

        if (chequeRoute) {
            // Handing the cheque over is a real movement, dated by the caller.
            LocalDate on = req.handedOverOn() != null ? req.handedOverOn() : req.paymentDate();
            movements.save(ChequeMovement.builder()
                    .bankCheckId(cheque.getId())
                    .fromHolder(cheque.getCurrentHolder())
                    .toHolder(SupplierEnums.Holder.SUPPLIER)
                    .counterparty(p.getSupplierName())
                    .movedOn(on)
                    .note("Handed to supplier against payment #" + p.getId())
                    .recordedBy(req.recordedBy())
                    .build());
            cheque.setCurrentHolder(SupplierEnums.Holder.SUPPLIER);
            cheques.save(cheque);
        }

        try {
            idempotency.saveAndFlush(PaymentIdempotencyKey.builder()
                    .key(req.idempotencyKey()).requestHash(hash).paymentId(p.getId()).build());
        } catch (DataIntegrityViolationException e) {
            throw new Problem(409, "That payment is already being recorded.");
        }
        return view(p);
    }

    /**
     * A cheque may back a NEW payment once an administrator has confirmed what it is and
     * where it is. That is an availability check, not a bank-clearance step. Nothing is
     * inferred from the legacy cleared flag, recipient text or fromWhom, and a legacy row
     * whose fields are still NULL is simply not usable yet.
     */
    private void assertChequeUsable(BankCheck c, SupplierEnums.Method method) {
        if (c.getIssuerType() == null || c.getCurrentHolder() == null) {
            throw new Problem(409, "Confirm this cheque's issuer and current holder before using it.");
        }
        if (c.getCurrentHolder() == SupplierEnums.Holder.SUPPLIER) {
            throw new Problem(409,
                    "The supplier already holds that cheque; record its return before handing it over again.");
        }
        SupplierEnums.IssuerType expected = method == SupplierEnums.Method.OWN_CHEQUE
                ? SupplierEnums.IssuerType.OWN : SupplierEnums.IssuerType.CLIENT;
        if (c.getIssuerType() != expected) {
            throw new Problem(400, "That cheque is a " + c.getIssuerType()
                    + " cheque; the chosen route expects " + expected + ".");
        }
    }

    private void applyAllocations(SupplierPayment p, List<AllocationInput> lines, String by, String reason) {
        if (lines == null || lines.isEmpty()) return;
        Set<Long> seen = new HashSet<>();
        BigDecimal total = BigDecimal.ZERO;
        for (AllocationInput l : lines) {
            if (l.masterWorksiteId() == null) throw new Problem(400, "Each allocation needs a masterWorksiteId.");
            if (l.amount() == null || l.amount().signum() <= 0) {
                throw new Problem(400, "Each allocation amount must be greater than zero.");
            }
            if (!seen.add(l.masterWorksiteId())) {
                throw new Problem(400, "A worksite appears twice in the allocations.");
            }
            total = total.add(l.amount());
        }
        if (total.compareTo(p.getAmount()) > 0) {
            throw new Problem(400, "Allocations exceed the payment amount.");
        }
        assertMastersExist(seen);
        for (AllocationInput l : lines) {
            allocations.save(SupplierPaymentAllocation.builder()
                    .paymentId(p.getId()).masterWorksiteId(l.masterWorksiteId())
                    .amount(CoverageRules.money(l.amount())).build());
            changes.save(AllocationChange.builder()
                    .paymentId(p.getId()).masterWorksiteId(l.masterWorksiteId())
                    .oldAmount(null).newAmount(CoverageRules.money(l.amount()))
                    .reason(reason).changedBy(by).build());
        }
    }

    /** Rejects the whole request before anything is written, so nothing is partially saved. */
    private void assertMastersExist(Set<Long> ids) {
        if (ids == null || ids.isEmpty()) return;
        Set<Long> found = agg.existingMasterIds(ids);
        for (Long id : ids) {
            if (!found.contains(id)) {
                throw new Problem(400, "Worksite #" + id + " does not exist.");
            }
        }
    }

    private static String hash(CreatePaymentRequest r) {
        String canonical = String.join("|",
                String.valueOf(r.method()), String.valueOf(r.amount()), String.valueOf(r.paymentDate()),
                String.valueOf(r.bankCheckId()), String.valueOf(r.reference()),
                r.allocations() == null ? "" : r.allocations().stream()
                        .sorted(Comparator.comparing(a -> String.valueOf(a.masterWorksiteId())))
                        .map(a -> a.masterWorksiteId() + ":" + a.amount())
                        .collect(Collectors.joining(",")));
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // =======================================================================
    // Corrections, allocation changes, custody, settlement
    // =======================================================================

    /**
     * Undo a tracker entry that should not have been recorded.
     *
     * This asserts nothing about the physical world: no cheque is handed back and no cash is
     * refunded. The link is kept for history and only the active marker is released, so the
     * cheque does not silently become available for a new handover while the supplier still
     * holds it. Record a custody movement separately if it really came back.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentView reverse(long id, ReverseRequest req) {
        SupplierPayment p = payments.lockById(id).orElseThrow(() -> new Problem(404, "No such payment."));
        if (p.isReversed()) return view(p);
        p.setReversedAt(Instant.now());
        p.setReversalReason(req == null ? null : req.reason());
        return view(payments.saveAndFlush(p));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public PaymentView changeAllocation(long paymentId, AllocationChangeRequest req) {
        SupplierPayment p = payments.lockById(paymentId).orElseThrow(() -> new Problem(404, "No such payment."));
        if (p.isReversed()) throw new Problem(409, "That payment was reversed.");
        if (req == null || req.masterWorksiteId() == null) throw new Problem(400, "masterWorksiteId is required.");

        BigDecimal next = req.amount() == null ? BigDecimal.ZERO : CoverageRules.money(req.amount());
        if (next.signum() < 0) throw new Problem(400, "An allocation cannot be negative.");
        // Removing an allocation (0) is allowed even for a master that has since been deleted,
        // so the check applies only when money is being attached.
        if (next.signum() > 0) assertMastersExist(Set.of(req.masterWorksiteId()));

        Optional<SupplierPaymentAllocation> existing =
                allocations.findByPaymentIdAndMasterWorksiteId(paymentId, req.masterWorksiteId());
        BigDecimal old = existing.map(SupplierPaymentAllocation::getAmount).orElse(null);

        BigDecimal othersTotal = allocations.findByPaymentId(paymentId).stream()
                .filter(a -> !a.getMasterWorksiteId().equals(req.masterWorksiteId()))
                .map(SupplierPaymentAllocation::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (othersTotal.add(next).compareTo(p.getAmount()) > 0) {
            throw new Problem(400, "Allocations exceed the payment amount.");
        }

        if (next.signum() == 0) {
            existing.ifPresent(allocations::delete);
        } else if (existing.isPresent()) {
            existing.get().setAmount(next);
            allocations.save(existing.get());
        } else {
            allocations.save(SupplierPaymentAllocation.builder()
                    .paymentId(paymentId).masterWorksiteId(req.masterWorksiteId()).amount(next).build());
        }
        changes.save(AllocationChange.builder()
                .paymentId(paymentId).masterWorksiteId(req.masterWorksiteId())
                .oldAmount(old).newAmount(next.signum() == 0 ? null : next)
                .reason(req.reason()).changedBy(req.changedBy()).build());
        return view(payments.findById(paymentId).orElseThrow());
    }

    /** Record a custody change that actually happened. Never inferred from anything else. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void recordMovement(long chequeId, MovementRequest req) {
        BankCheck c = cheques.findById(chequeId).orElseThrow(() -> new Problem(404, "No such cheque."));
        if (req == null || req.toHolder() == null) throw new Problem(400, "toHolder is required.");
        if (req.movedOn() == null) throw new Problem(400, "movedOn is required.");
        movements.save(ChequeMovement.builder()
                .bankCheckId(chequeId)
                .fromHolder(req.fromHolder() != null ? req.fromHolder() : c.getCurrentHolder())
                .toHolder(req.toHolder()).counterparty(req.counterparty())
                .movedOn(req.movedOn()).note(req.note()).recordedBy(req.recordedBy()).build());
        c.setCurrentHolder(req.toHolder());
        cheques.save(c);
    }

    /** Confirm what a cheque is and where it is. Creates no payment and moves no balance. */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ChequeOption verify(long chequeId, VerifyChequeRequest req) {
        BankCheck c = cheques.findById(chequeId).orElseThrow(() -> new Problem(404, "No such cheque."));
        if (req == null || req.issuerType() == null || req.currentHolder() == null) {
            throw new Problem(400, "issuerType and currentHolder are required.");
        }
        c.setIssuerType(req.issuerType());
        c.setCurrentHolder(req.currentHolder());
        c.setAvailabilityVerifiedAt(Instant.now());
        c.setAvailabilityVerifiedBy(req.verifiedBy());
        return option(cheques.saveAndFlush(c));
    }

    // =======================================================================
    // Reads
    // =======================================================================

    @Transactional(readOnly = true)
    public List<ChequeOption> availableForHandover(SupplierEnums.IssuerType issuerType) {
        return cheques.findAll().stream()
                .filter(c -> payments.findByActiveBankCheckId(c.getId()).isEmpty())
                .filter(c -> c.getCurrentHolder() != SupplierEnums.Holder.SUPPLIER)
                .filter(c -> issuerType == null || c.getIssuerType() == null || c.getIssuerType() == issuerType)
                .map(this::option)
                .sorted(Comparator.comparing(ChequeOption::needsVerification)
                        .thenComparing(o -> o.dueDate() == null ? LocalDate.MAX : o.dueDate()))
                .toList();
    }

    private ChequeOption option(BankCheck c) {
        Long active = payments.findByActiveBankCheckId(c.getId()).map(SupplierPayment::getId).orElse(null);
        return new ChequeOption(c.getId(), c.getSerialNumber(), c.getAmount(), c.getDueDate(), c.getFromWhom(),
                c.getIssuerType(), c.getCurrentHolder(),
                c.getIssuerType() == null || c.getCurrentHolder() == null,
                payments.existsByBankCheckId(c.getId()), active);
    }

    @Transactional(readOnly = true)
    public UnassignedSuppliesView unassignedSupplies() {
        return new UnassignedSuppliesView(agg.unassignedSuppliesTotal(), agg.unassignedSuppliesByWorksite());
    }

    @Transactional(readOnly = true)
    public List<PaymentView> list(LocalDate from, LocalDate to) {
        LocalDate f = from != null ? from : LocalDate.of(1970, 1, 1);
        LocalDate t = to != null ? to : LocalDate.of(2999, 12, 31);
        return payments.findByPaymentDateBetweenOrderByPaymentDateDescIdDesc(f, t)
                .stream().map(this::view).toList();
    }

    @Transactional(readOnly = true)
    public PaymentView get(long id) {
        return view(payments.findById(id).orElseThrow(() -> new Problem(404, "No such payment.")));
    }

    @Transactional(readOnly = true)
    public List<ChequeMovement> movementsOf(long chequeId) {
        return movements.findByBankCheckIdOrderByMovedOnAscIdAsc(chequeId);
    }

    @Transactional(readOnly = true)
    public List<AllocationChange> historyOf(long paymentId) {
        return changes.findByPaymentIdOrderByIdAsc(paymentId);
    }

    private PaymentView view(SupplierPayment p) {
        Map<Long, String> names = agg.masterNames();
        List<AllocationView> lines = allocations.findByPaymentId(p.getId()).stream()
                .map(a -> new AllocationView(a.getId(), a.getMasterWorksiteId(),
                        names.getOrDefault(a.getMasterWorksiteId(), "#" + a.getMasterWorksiteId()),
                        CoverageRules.money(a.getAmount())))
                .sorted(Comparator.comparing(AllocationView::masterWorksiteId))
                .toList();
        BigDecimal allocated = lines.stream().map(AllocationView::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new PaymentView(p.getId(), p.getSupplierName(), p.getPaymentDate(), CoverageRules.money(p.getAmount()),
                p.getMethod(), p.getBankCheckId(), CoverageRules.isEffective(p),
                CoverageRules.money(allocated), CoverageRules.money(p.getAmount().subtract(allocated)),
                p.isReversed(), p.getReversalReason(), p.getReversedAt(), p.getReference(), p.getNotes(), lines);
    }
}
