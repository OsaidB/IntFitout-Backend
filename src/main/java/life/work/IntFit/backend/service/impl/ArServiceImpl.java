// File: life/work/IntFit/backend/service/impl/ArServiceImpl.java
package life.work.IntFit.backend.service.impl;

import life.work.IntFit.backend.dto.billing.AR.*;
import life.work.IntFit.backend.model.entity.ArPayment;
import life.work.IntFit.backend.model.entity.ArPaymentAllocation;
import life.work.IntFit.backend.model.entity.ArCharge; // <-- NEW entity
import life.work.IntFit.backend.repository.ArPaymentAllocationRepository;
import life.work.IntFit.backend.repository.ArPaymentRepository;
import life.work.IntFit.backend.repository.ArChargeRepository; // <-- NEW repo
import life.work.IntFit.backend.service.ArService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Service
public class ArServiceImpl implements ArService {

    private final ArChargeRepository chargeRepo;
    private final ArPaymentRepository paymentRepo;
    private final ArPaymentAllocationRepository allocRepo;

    public ArServiceImpl(
            ArChargeRepository chargeRepo,
            ArPaymentRepository paymentRepo,
            ArPaymentAllocationRepository allocRepo
    ) {
        this.chargeRepo = chargeRepo;
        this.paymentRepo = paymentRepo;
        this.allocRepo   = allocRepo;
    }

    // =========================================================
    // Statement (charges + payments)  [to = inclusive]
    // =========================================================
    @Override
    @Transactional(readOnly = true)
    public StatementDTO getStatement(Long masterWorksiteId, LocalDate from, LocalDate toInclusive) {
        if (masterWorksiteId == null) throw bad("masterWorksiteId is required");
        if (from == null || toInclusive == null || toInclusive.isBefore(from)) throw bad("Invalid date range");

        final LocalDate toEx = toInclusive.plusDays(1);

        // Opening = charges before 'from' - payments before 'from'
        BigDecimal openCharges  = n(chargeRepo.sumBefore(masterWorksiteId, from));
        BigDecimal openPayments = n(paymentRepo.sumBefore(masterWorksiteId, from));
        BigDecimal openingBalance = openCharges.subtract(openPayments);

        // Charges in range with paid/remaining as-of 'toInclusive'
        List<ArCharge> charges = chargeRepo.findInRange(masterWorksiteId, from, toEx);
        List<StatementChargeDTO> chargeDTOs = new ArrayList<>(charges.size());
        for (ArCharge c : charges) {
            BigDecimal paidAsOf   = n(allocRepo.sumForChargeAsOf(c.getId(), toInclusive));
            BigDecimal remaining  = maxZero(n(c.getAmount()).subtract(paidAsOf));

            StatementChargeDTO dto = new StatementChargeDTO();
            dto.id         = c.getId();
            dto.dateISO    = c.getDate().toString();
            dto.description= c.getDescription();
            dto.amount     = n(c.getAmount());
            dto.paid       = paidAsOf;
            dto.remaining  = remaining;
            chargeDTOs.add(dto);
        }

        // Payments in range (allocations optional for the UI)
        List<ArPayment> pays = paymentRepo.findInRange(masterWorksiteId, from, toEx);
        List<StatementPaymentDTO> payDTOs = new ArrayList<>(pays.size());
        for (ArPayment p : pays) payDTOs.add(toPaymentDTO(p));

        StatementDTO out = new StatementDTO();
        out.openingBalance = openingBalance;
        out.adjustments    = BigDecimal.ZERO;
        out.charges        = chargeDTOs;
        out.payments       = payDTOs;
        return out;
    }

    // =========================================================
    // Compatibility: "Open Invoices" -> returns Open CHARGES
    // (keeps your interface compiling; mapper uses Invoice-shaped DTO)
    // =========================================================
    @Override
    @Transactional(readOnly = true)
    public List<StatementInvoiceDTO> getOpenInvoices(Long masterWorksiteId, LocalDate asOf) {
        if (masterWorksiteId == null) throw bad("masterWorksiteId is required");
        if (asOf == null) asOf = LocalDate.now();

        List<ArCharge> candidates = chargeRepo.findOnOrBefore(masterWorksiteId, asOf);
        List<StatementInvoiceDTO> out = new ArrayList<>();
        for (ArCharge c : candidates) {
            BigDecimal paidAsOf  = n(allocRepo.sumForChargeAsOf(c.getId(), asOf));
            BigDecimal remaining = maxZero(n(c.getAmount()).subtract(paidAsOf));
            if (remaining.signum() > 0) {
                StatementInvoiceDTO d = new StatementInvoiceDTO();
                d.id         = c.getId();
                d.number     = "CHG-" + c.getId();           // compatibility
                d.dateISO    = c.getDate().toString();
                d.total      = n(c.getAmount());
                d.paid       = paidAsOf;
                d.remaining  = remaining;
                out.add(d);
            }
        }
        return out;
    }

    // =========================================================
    // Manual allocation (DTO still says "invoiceId" -> treat as chargeId)
    // =========================================================
    @Override
    @Transactional
    public void allocatePayment(Long paymentId, AllocateRequestDTO body) {
        ArPayment payment = paymentRepo.findById(paymentId)
                .orElseThrow(() -> notFound("Payment not found"));

        if (body == null || body.allocations == null || body.allocations.isEmpty()) {
            throw bad("No allocations provided");
        }

        BigDecimal alreadyAllocated = n(paymentRepo.sumAllocationsForPayment(payment.getId()));
        BigDecimal available        = n(payment.getAmount()).subtract(alreadyAllocated);

        BigDecimal requested = BigDecimal.ZERO;
        for (AllocateRequestDTO.Line l : body.allocations) {
            if (l.invoiceId == null || l.amount == null || l.amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw bad("Invalid allocation line");
            }
            requested = requested.add(l.amount);
        }
        if (requested.compareTo(available) > 0) {
            throw bad("Allocations exceed available payment amount");
        }

        // Validate each target charge remaining as of today
        LocalDate today = LocalDate.now();
        for (AllocateRequestDTO.Line l : body.allocations) {
            Long chargeId = l.invoiceId; // compatibility: invoiceId means chargeId now
            ArCharge ch = chargeRepo.findById(chargeId)
                    .orElseThrow(() -> notFound("Charge #" + chargeId + " not found"));
            BigDecimal paidAsOf  = n(allocRepo.sumForChargeAsOf(ch.getId(), today));
            BigDecimal remaining = maxZero(n(ch.getAmount()).subtract(paidAsOf));
            if (l.amount.compareTo(remaining) > 0) {
                throw bad("Allocation " + l.amount + " exceeds remaining " + remaining + " for charge #" + ch.getId());
            }
        }

        // Persist allocations
        for (AllocateRequestDTO.Line l : body.allocations) {
            ArPaymentAllocation a = ArPaymentAllocation.builder()
                    .payment(payment)
                    .chargeId(l.invoiceId) // renamed column in DB
                    .amount(l.amount)
                    .build();
            allocRepo.save(a);
        }
    }

    // =========================================================
    // Payment create + FIFO auto-allocate
    // =========================================================
    @Override
    @Transactional
    public ArPaymentDTO createPayment(CreatePaymentDTO b) {
        if (b == null || b.masterWorksiteId == null || b.amount == null || b.dateISO == null)
            throw bad("masterWorksiteId, amount, dateISO are required");

        ArPayment p = ArPayment.builder()
                .masterWorksiteId(b.masterWorksiteId)
                .amount(b.amount)
                .date(LocalDate.parse(b.dateISO))
                .method(b.method)
                .reference(b.reference)
                .notes(b.notes)
                .build();
        p = paymentRepo.save(p);

        // Idempotent: only if this payment still has no allocations
        if (!allocRepo.existsByPayment_Id(p.getId())) {
            autoAllocateFIFO(p);
        }
        return toDto(p);
    }

    // =========================================================
    // Payments list (inclusive range)
    // =========================================================
    @Override
    @Transactional(readOnly = true)
    public List<ArPaymentDTO> listPayments(LocalDate from, LocalDate toInclusive, Long masterWorksiteId) {
        if (from == null || toInclusive == null || toInclusive.isBefore(from)) throw bad("Invalid date range");
        final LocalDate toEx = toInclusive.plusDays(1);

        List<ArPayment> rows = (masterWorksiteId != null)
                ? paymentRepo.findInRange(masterWorksiteId, from, toEx)
                : paymentRepo.findInRange(null, from, toEx); // If you support null master ID in repo, else add another repo method

        List<ArPaymentDTO> out = new ArrayList<>(rows.size());
        for (ArPayment p : rows) out.add(toDto(p));
        return out;
    }

    // =========================================================
    // Charges CRUD (used by your “Manage Charges” UI)
    // =========================================================
    @Transactional
    public StatementChargeDTO createCharge(Long masterWorksiteId, String dateISO, String desc, BigDecimal amount) {
        if (masterWorksiteId == null || dateISO == null || amount == null) throw bad("masterWorksiteId, dateISO, amount are required");
        ArCharge c = ArCharge.builder()
                .masterWorksiteId(masterWorksiteId)
                .date(LocalDate.parse(dateISO))
                .description(desc)
                .amount(amount)
                .build();
        c = chargeRepo.save(c);

        StatementChargeDTO dto = new StatementChargeDTO();
        dto.id = c.getId();
        dto.dateISO = c.getDate().toString();
        dto.description = c.getDescription();
        dto.amount = n(c.getAmount());
        dto.paid = BigDecimal.ZERO;
        dto.remaining = n(c.getAmount());
        return dto;
    }

    @Transactional
    public void updateCharge(Long id, String dateISO, String desc, BigDecimal amount) {
        ArCharge c = chargeRepo.findById(id).orElseThrow(() -> notFound("Charge not found"));
        if (dateISO != null) c.setDate(LocalDate.parse(dateISO));
        if (desc != null) c.setDescription(desc);
        if (amount != null) c.setAmount(amount);
        chargeRepo.save(c);
    }

    @Transactional
    public void deleteCharge(Long id) {
        chargeRepo.deleteById(id);
    }

    // =========================================================
    // Internals
    // =========================================================
    private void autoAllocateFIFO(ArPayment payment) {
        BigDecimal remaining = n(payment.getAmount());
        if (remaining.signum() <= 0) return;

        // All charges up to payment date, oldest first
        List<ArCharge> candidates = chargeRepo.findOnOrBefore(payment.getMasterWorksiteId(), payment.getDate());

        for (ArCharge ch : candidates) {
            if (remaining.signum() <= 0) break;

            BigDecimal paidToChargeAsOf = n(allocRepo.sumForChargeAsOf(ch.getId(), payment.getDate()));
            BigDecimal chargeRemaining  = n(ch.getAmount()).subtract(paidToChargeAsOf);
            if (chargeRemaining.signum() <= 0) continue;

            BigDecimal allocAmt = chargeRemaining.min(remaining);
            ArPaymentAllocation alloc = ArPaymentAllocation.builder()
                    .payment(payment)
                    .chargeId(ch.getId())
                    .amount(allocAmt)
                    .build();
            allocRepo.save(alloc);

            remaining = remaining.subtract(allocAmt);
        }
        // any leftover remains unallocated (still reduces overall balance)
    }

    private static StatementPaymentDTO toPaymentDTO(ArPayment p) {
        StatementPaymentDTO d = new StatementPaymentDTO();
        d.id        = p.getId();
        d.dateISO   = p.getDate() != null ? p.getDate().toString() : null;
        d.amount    = n(p.getAmount());
        d.method    = p.getMethod();
        d.reference = p.getReference();
        d.notes     = p.getNotes();
        return d;
    }

    private static ArPaymentDTO toDto(ArPayment p) {
        ArPaymentDTO d = new ArPaymentDTO();
        d.id = p.getId();
        d.masterWorksiteId = p.getMasterWorksiteId();
        d.dateISO = p.getDate() != null ? p.getDate().toString() : null;
        d.amount = n(p.getAmount());
        d.method = p.getMethod();
        d.reference = p.getReference();
        d.notes = p.getNotes();
        d.createdAt = p.getCreatedAt() != null ? p.getCreatedAt().toString() : null;
        return d;
    }

    private static BigDecimal n(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal maxZero(BigDecimal v) {
        return v.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : v;
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    private static ResponseStatusException notFound(String m) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, m);
    }
}
