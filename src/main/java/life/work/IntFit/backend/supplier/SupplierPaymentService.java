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
    public WorksitePage browse(String snapshotId, int offset, int limit, LocalDate today,
                               WorksiteQuery query) {
        WorksiteQuery qy = query == null ? WorksiteQuery.defaults() : query;
        LocalDate now = today == null ? LocalDate.now(ZONE) : today;
        String criteria = qy.fingerprint();

        WorksiteBrowseSnapshots.Snapshot snap;
        if (snapshotId == null || snapshotId.isBlank()) {
            snap = snapshots.createOrdered(rank(qy, now), criteria);
        } else {
            snap = snapshots.get(snapshotId)
                    .orElseThrow(() -> new Problem(410, "That browsing session expired. Refresh the list."));
            // Paging a snapshot under different criteria would answer from the wrong ordering.
            if (!criteria.equals(snap.criteria())) {
                throw new Problem(409, "The filters changed. Refresh the list to continue.");
            }
        }

        int from = Math.max(0, offset);
        int size = Math.min(Math.max(1, limit), 100);
        List<WorksiteBrowseSnapshots.Entry> slice = snap.slice(from, size);

        Context ctx = context(qy);
        List<WorksiteRow> rows = slice.stream()
                .map(e -> row(e.masterId(), e.lastActivityOn(),
                        qy.groupedByRecency() ? e.group() : null, ctx))
                .toList();

        int next = from + slice.size();
        List<Long> matchingIds = snap.entries().stream().map(WorksiteBrowseSnapshots.Entry::masterId).toList();
        return new WorksitePage(snap.id(), rows, next < snap.size() ? next : null,
                next < snap.size(), snap.size(), agg.unassignedSuppliesTotal(),
                qy.groupedByRecency(), summaryFor(qy, matchingIds, ctx));
    }

    /** The same matching set the list uses, summarised. */
    @Transactional(readOnly = true)
    public Summary summary(WorksiteQuery query, LocalDate today) {
        WorksiteQuery qy = query == null ? WorksiteQuery.defaults() : query;
        LocalDate now = today == null ? LocalDate.now(ZONE) : today;
        Context ctx = context(qy);
        List<Long> ids = rank(qy, now).stream().map(WorksiteBrowseSnapshots.Entry::masterId).toList();
        return summaryFor(qy, ids, ctx);
    }

    /** Everything the rows and summary need, loaded once per request. */
    private record Context(
            WorksiteQuery q,
            Map<Long, BigDecimal> supplies, Map<Long, BigDecimal> receipts,
            Map<Long, BigDecimal> paid, Map<Long, String> names,
            Map<Long, BigDecimal> periodSupplies, Map<Long, BigDecimal> periodAllocated
    ) { }

    private Context context(WorksiteQuery qy) {
        boolean period = qy.hasPeriod();
        return new Context(qy,
                agg.suppliesByMaster(), agg.receiptsByMaster(), paidByMaster(), agg.masterNames(),
                period ? agg.suppliesByMasterBetween(qy.from(), qy.to()) : Map.of(),
                period ? agg.allocatedByMasterBetween(qy.from(), qy.to()) : Map.of());
    }

    /**
     * Filter, then sort, across the whole eligible dataset - never within a page.
     *
     * Period membership comes from the existence of dated records, not from a non-zero net:
     * a delivery and an applied return can cancel out while the period genuinely had
     * activity, and a fully paid project still belongs in that week's list.
     */
    private List<WorksiteBrowseSnapshots.Entry> rank(WorksiteQuery qy, LocalDate now) {
        Map<Long, LocalDate> lastActivity = agg.lastActivityByMaster();
        Context ctx = context(qy);

        Set<Long> candidates = new TreeSet<>();
        if (qy.hasPeriod()) {
            candidates.addAll(agg.mastersWithSupplyRecordsBetween(qy.from(), qy.to()));
            candidates.addAll(agg.mastersWithPaymentRecordsBetween(qy.from(), qy.to()));
        } else {
            candidates.addAll(lastActivity.keySet());
            candidates.addAll(ctx.supplies().keySet());
            candidates.addAll(ctx.paid().keySet());
        }

        Map<Long, String[]> text = (qy.q() == null) ? Map.of() : agg.searchableMasterText();
        LocalDate weekStart = WorksiteQuery.startOfWeek(now);

        List<WorksiteRow> matched = candidates.stream()
                .filter(id -> matchesText(id, qy.q(), text, ctx.names()))
                .filter(id -> matchesStatus(id, qy.status(), ctx))
                .filter(id -> matchesLastActive(id, qy.lastActiveWithinDays(), lastActivity, now))
                .map(id -> row(id, lastActivity.get(id),
                        WorksiteBrowseSnapshots.groupFor(lastActivity.get(id), now, weekStart), ctx))
                .toList();

        Comparator<WorksiteRow> cmp = comparatorFor(qy);
        return matched.stream().sorted(cmp)
                .map(r -> new WorksiteBrowseSnapshots.Entry(
                        r.masterWorksiteId(), r.lastActivityOn(), r.group()))
                .toList();
    }

    /**
     * REMAINING, CREDIT and NAME are all-time measures. The period sorts fall back to the
     * all-time amounts when no period is chosen, so an amount sort never orders by a column
     * that is null on every row.
     */
    private Comparator<WorksiteRow> comparatorFor(WorksiteQuery qy) {
        boolean period = qy.hasPeriod();
        Comparator<WorksiteRow> base = switch (qy.sort()) {
            case REMAINING -> Comparator.comparing(WorksiteRow::remainingToCover);
            case CREDIT -> Comparator.comparing(WorksiteRow::credit);
            case PERIOD_SUPPLIES -> Comparator.comparing(
                    r -> period ? nz(r.periodSupplies()) : nz(r.supplies()));
            case PERIOD_PAID -> Comparator.comparing(
                    r -> period ? nz(r.periodAllocated()) : nz(r.paid()));
            case NAME -> Comparator.comparing(r -> r.name() == null ? "" : r.name(),
                    java.text.Collator.getInstance(new java.util.Locale("ar")));
            case RECENT_ACTIVITY -> Comparator.comparing(WorksiteRow::lastActivityOn,
                    Comparator.nullsLast(Comparator.naturalOrder()));
        };
        // Newest first is the natural reading of "recent activity", so it inverts by default.
        boolean ascending = qy.sort() == WorksiteQuery.Sort.RECENT_ACTIVITY
                ? qy.ascending() : qy.ascending();
        Comparator<WorksiteRow> ordered = ascending ? base : base.reversed();
        if (qy.sort() == WorksiteQuery.Sort.RECENT_ACTIVITY && !qy.ascending()) {
            ordered = base.reversed();
        }
        return ordered.thenComparing(WorksiteRow::masterWorksiteId);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private boolean matchesText(long id, String needle, Map<Long, String[]> text, Map<Long, String> names) {
        if (needle == null) return true;
        String n = normalize(needle);
        String[] parts = text.get(id);
        String hay = parts == null
                ? normalize(names.getOrDefault(id, ""))
                : normalize(parts[0] + " " + parts[1] + " " + parts[2]);
        return hay.contains(n) || String.valueOf(id).contains(n);
    }

    /**
     * Arabic is written with and without diacritics and with several forms of alef, so a
     * search for a name typed one way must still find it stored another.
     */
    static String normalize(String raw) {
        if (raw == null) return "";
        String s = raw.toLowerCase(java.util.Locale.ROOT).trim();
        s = s.replaceAll("[\\u064B-\\u0652\\u0640]", "");   // harakat and tatweel
        s = s.replaceAll("[\\u0622\\u0623\\u0625\\u0671]", "\\u0627"); // alef forms
        s = s.replace('\u0649', '\u064A').replace('\u0629', '\u0647');       // alef maqsura, ta marbuta
        return s.replaceAll("\\s+", " ");
    }

    private boolean matchesStatus(long id, WorksiteQuery.Status st, Context ctx) {
        if (st == null || st == WorksiteQuery.Status.ALL) return true;
        BigDecimal sup = nz(ctx.supplies().get(id));
        BigDecimal paid = nz(ctx.paid().get(id));
        BigDecimal remaining = CoverageRules.remainingToCover(sup, paid);
        BigDecimal credit = CoverageRules.credit(sup, paid);
        return switch (st) {
            case STILL_TO_PAY -> remaining.signum() > 0;
            case NO_PAYMENTS -> paid.signum() == 0 && sup.signum() > 0;
            case PARTLY_PAID -> paid.signum() > 0 && remaining.signum() > 0;
            case PAID_IN_FULL -> sup.signum() > 0 && remaining.signum() == 0 && credit.signum() == 0;
            case CREDIT -> credit.signum() > 0;
            case ALL -> true;
        };
    }

    private boolean matchesLastActive(long id, Integer withinDays,
                                      Map<Long, LocalDate> lastActivity, LocalDate now) {
        if (withinDays == null) return true;
        LocalDate last = lastActivity.get(id);
        if (last == null) return false;
        return !last.isBefore(now.minusDays(withinDays));
    }

    private Summary summaryFor(WorksiteQuery qy, List<Long> matchingIds, Context ctx) {
        BigDecimal matchSupplies = BigDecimal.ZERO, matchAllocated = BigDecimal.ZERO;
        BigDecimal stillToPay = BigDecimal.ZERO, credit = BigDecimal.ZERO;
        int noPayments = 0, inCredit = 0;

        for (Long id : matchingIds) {
            BigDecimal sup = nz(ctx.supplies().get(id));
            BigDecimal paid = nz(ctx.paid().get(id));
            stillToPay = stillToPay.add(CoverageRules.remainingToCover(sup, paid));
            BigDecimal c = CoverageRules.credit(sup, paid);
            credit = credit.add(c);
            if (paid.signum() == 0 && sup.signum() > 0) noPayments++;
            if (c.signum() > 0) inCredit++;
            if (qy.hasPeriod()) {
                matchSupplies = matchSupplies.add(nz(ctx.periodSupplies().get(id)));
                matchAllocated = matchAllocated.add(nz(ctx.periodAllocated().get(id)));
            }
        }

        boolean period = qy.hasPeriod();
        return new Summary(
                matchingIds.size(), period, qy.from(), qy.to(),
                period ? CoverageRules.money(matchSupplies) : null,
                period ? CoverageRules.money(matchAllocated) : null,
                period ? agg.actualPaidBetween(qy.from(), qy.to()) : null,
                period ? agg.unallocatedPaidBetween(qy.from(), qy.to()) : null,
                period ? agg.unassignedSuppliesBetween(qy.from(), qy.to()) : null,
                CoverageRules.money(stillToPay), CoverageRules.money(credit),
                noPayments, inCredit);
    }

    /**
     * Every worksite that still needs attention: something left to cover, or a credit.
     * Inactive worksites are included.
     */
    @Transactional(readOnly = true)
    public List<WorksiteRow> outstanding() {
        Context ctx = context(WorksiteQuery.defaults());
        Map<Long, LocalDate> last = agg.lastActivityByMaster();

        Set<Long> ids = new TreeSet<>();
        ids.addAll(ctx.supplies().keySet());
        ids.addAll(ctx.paid().keySet());

        return ids.stream()
                .map(id -> row(id, last.get(id), null, ctx))
                .filter(r -> r.remainingToCover().signum() > 0 || r.credit().signum() > 0)
                .sorted(Comparator.comparing(WorksiteRow::remainingToCover).reversed()
                        .thenComparing(WorksiteRow::masterWorksiteId))
                .toList();
    }

    private WorksiteRow row(long id, LocalDate lastOn, String group, Context ctx) {
        BigDecimal sup = CoverageRules.money(ctx.supplies().getOrDefault(id, BigDecimal.ZERO));
        BigDecimal paid = CoverageRules.money(ctx.paid().getOrDefault(id, BigDecimal.ZERO));
        boolean period = ctx.q().hasPeriod();
        return new WorksiteRow(id, ctx.names().getOrDefault(id, "#" + id), lastOn, group,
                sup, CoverageRules.money(ctx.receipts().getOrDefault(id, BigDecimal.ZERO)),
                paid,
                CoverageRules.remainingToCover(sup, paid),
                CoverageRules.credit(sup, paid),
                // A period total may legitimately be negative when applied returns exceed
                // supply, so it is reported as it stands rather than floored at zero.
                period ? CoverageRules.money(ctx.periodSupplies().getOrDefault(id, BigDecimal.ZERO)) : null,
                period ? CoverageRules.money(ctx.periodAllocated().getOrDefault(id, BigDecimal.ZERO)) : null);
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
