// File: life/work/IntFit/backend/service/impl/ArServiceImpl.java
package life.work.IntFit.backend.service.impl;

import life.work.IntFit.backend.dto.*;
import life.work.IntFit.backend.model.entity.ArPayment;
import life.work.IntFit.backend.model.entity.ArPaymentAllocation;
import life.work.IntFit.backend.model.entity.Invoice;
import life.work.IntFit.backend.repository.ArPaymentAllocationRepository;
import life.work.IntFit.backend.repository.ArPaymentRepository;
import life.work.IntFit.backend.repository.InvoiceRepository;
import life.work.IntFit.backend.service.ArService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import life.work.IntFit.backend.dto.ArPaymentDTO;
import life.work.IntFit.backend.dto.CreatePaymentDTO;

@Service
public class ArServiceImpl implements ArService {

    private final InvoiceRepository invoiceRepo;
    private final ArPaymentRepository paymentRepo;
    private final ArPaymentAllocationRepository allocRepo;

    public ArServiceImpl(
            InvoiceRepository invoiceRepo,
            ArPaymentRepository paymentRepo,
            ArPaymentAllocationRepository allocRepo
    ) {
        this.invoiceRepo = invoiceRepo;
        this.paymentRepo = paymentRepo;
        this.allocRepo   = allocRepo;
    }

    @Override
    public StatementDTO getStatement(Long masterWorksiteId, LocalDate from, LocalDate to) {
        if (masterWorksiteId == null) {
            throw bad("masterWorksiteId is required");
        }
        if (from == null || to == null || !to.isAfter(from) ) {
            throw bad("Invalid date range");
        }
        final LocalDateTime fromDt = from.atStartOfDay();
        final LocalDateTime toDt   = LocalDateTime.of(to, LocalTime.MIDNIGHT); // [from, to)

        StatementDTO out = new StatementDTO();

        BigDecimal invBefore = nz(invoiceRepo.sumTotalsBeforeMaster(masterWorksiteId, fromDt));
        BigDecimal payBefore = nz(paymentRepo.sumByMasterAndDateBefore(masterWorksiteId, from.minusDays(0)));
        out.openingBalance = invBefore.subtract(payBefore);

        // Invoices in range, compute paid/remaining as of 'to'
        List<Invoice> inRange = invoiceRepo.findByMasterBetween(masterWorksiteId, fromDt, toDt);
        for (Invoice inv : inRange) {
            StatementInvoiceDTO line = new StatementInvoiceDTO();
            line.id = inv.getId();
            line.number = "INV-" + inv.getId();             // your entity has no 'number' field
            line.dateISO = safeDateIso(inv);
            line.total = money(inv);                         // <-- convert Double -> BigDecimal
            BigDecimal paid = nz(allocRepo.sumAllocationsForInvoiceAsOf(inv.getId(), to));
            line.paid = paid;
            line.remaining = maxZero(line.total.subtract(paid));
            out.invoices.add(line);
        }

        // Payments in range
        List<ArPayment> pays = paymentRepo.findByMasterWorksiteIdAndDateBetween(masterWorksiteId, from, to.minusDays(0));
        for (ArPayment p : pays) {
            StatementPaymentDTO pl = new StatementPaymentDTO();
            pl.id = p.getId();
            pl.dateISO = p.getDate().toString();
            pl.amount = nz(p.getAmount());
            pl.method = p.getMethod();
            pl.reference = p.getReference();
            pl.notes = p.getNotes();
            if (p.getAllocations() != null) {
                for (ArPaymentAllocation a : p.getAllocations()) {
                    StatementPaymentAllocationDTO al = new StatementPaymentAllocationDTO();
                    al.invoiceId = a.getInvoiceId();
                    al.amount = nz(a.getAmount());
                    pl.allocations.add(al);
                }
            }
            out.payments.add(pl);
        }

        out.adjustments = BigDecimal.ZERO;
        return out;
    }

    @Override
    public List<StatementInvoiceDTO> getOpenInvoices(Long masterWorksiteId, LocalDate asOf) {
        if (masterWorksiteId == null) throw bad("masterWorksiteId is required");
        if (asOf == null) asOf = LocalDate.now();

        LocalDateTime asOfDt = LocalDateTime.of(asOf, LocalTime.MAX);
        List<Invoice> all = invoiceRepo.findAllByMasterUpTo(masterWorksiteId, asOfDt);

        List<StatementInvoiceDTO> out = new ArrayList<>();
        for (Invoice inv : all) {
            BigDecimal total = money(inv);                   // <-- convert Double -> BigDecimal
            BigDecimal paid  = nz(allocRepo.sumAllocationsForInvoiceAsOf(inv.getId(), asOf));
            BigDecimal rem   = maxZero(total.subtract(paid));
            if (rem.compareTo(BigDecimal.ZERO) > 0) {
                StatementInvoiceDTO line = new StatementInvoiceDTO();
                line.id = inv.getId();
                line.number = "INV-" + inv.getId();
                line.dateISO = safeDateIso(inv);
                line.total = total;
                line.paid = paid;
                line.remaining = rem;
                out.add(line);
            }
        }
        return out;
    }

    @Override
    public void allocatePayment(Long paymentId, AllocateRequestDTO body) {
        ArPayment payment = paymentRepo.findById(paymentId)
                .orElseThrow(() -> notFound("Payment not found"));

        if (body == null || body.allocations == null || body.allocations.isEmpty()) {
            throw bad("No allocations provided");
        }

        BigDecimal alreadyAllocated = nz(paymentRepo.sumAllocationsForPayment(payment.getId()));
        BigDecimal available = nz(payment.getAmount()).subtract(alreadyAllocated);

        BigDecimal toAllocate = BigDecimal.ZERO;
        for (AllocateRequestDTO.Line l : body.allocations) {
            if (l.invoiceId == null || l.amount == null || l.amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw bad("Invalid allocation line");
            }
            toAllocate = toAllocate.add(l.amount);
        }
        if (toAllocate.compareTo(available) > 0) {
            throw bad("Allocations exceed available payment amount");
        }

        // invoice remaining check (as of today)
        LocalDate today = LocalDate.now();
        for (AllocateRequestDTO.Line l : body.allocations) {
            Invoice inv = invoiceRepo.findById(l.invoiceId)
                    .orElseThrow(() -> notFound("Invoice #" + l.invoiceId + " not found"));
            BigDecimal total = money(inv);                   // <-- convert Double -> BigDecimal
            BigDecimal paid  = nz(allocRepo.sumAllocationsForInvoiceAsOf(inv.getId(), today));
            BigDecimal rem   = maxZero(total.subtract(paid));
            if (l.amount.compareTo(rem) > 0) {
                throw bad("Allocation " + l.amount + " exceeds remaining " + rem + " for invoice #" + inv.getId());
            }
        }

        for (AllocateRequestDTO.Line l : body.allocations) {
            ArPaymentAllocation a = ArPaymentAllocation.builder()
                    .payment(payment)
                    .invoiceId(l.invoiceId)
                    .amount(l.amount)
                    .build();
            allocRepo.save(a);
        }
    }

    // ------- helpers -------
    private static ResponseStatusException bad(String m) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, m); }
    private static ResponseStatusException notFound(String m) { return new ResponseStatusException(HttpStatus.NOT_FOUND, m); }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }
    private static BigDecimal maxZero(BigDecimal v) { return v.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : v; }

    // Convert any Number (Double in your entity) to BigDecimal safely
    private static BigDecimal bd(Number n) {
        return n == null ? BigDecimal.ZERO : BigDecimal.valueOf(n.doubleValue());
    }

    // Pull total from Invoice; fallback to netTotal; convert to BigDecimal
    private static BigDecimal money(Invoice inv) {
        Double t = inv.getTotal();
        if (t == null) t = inv.getNetTotal();
        return bd(t);
    }

    private static String safeDateIso(Invoice inv) {
        return inv.getDate() == null ? null : inv.getDate().toLocalDate().toString();
    }


    @Override
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
        return toDto(p);
    }

    @Override
    public List<ArPaymentDTO> listPayments(LocalDate from, LocalDate to, Long masterWorksiteId) {
        if (from == null || to == null || !to.isAfter(from)) throw bad("Invalid date range");
        List<ArPayment> rows = (masterWorksiteId != null)
                ? paymentRepo.findByMasterWorksiteIdAndDateBetween(masterWorksiteId, from, to.minusDays(0))
                : paymentRepo.findByDateBetween(from, to.minusDays(0));
        List<ArPaymentDTO> out = new ArrayList<>();
        for (ArPayment p : rows) out.add(toDto(p));
        return out;
    }

    private static ArPaymentDTO toDto(ArPayment p) {
        ArPaymentDTO d = new ArPaymentDTO();
        d.id = p.getId();
        d.masterWorksiteId = p.getMasterWorksiteId();
        d.dateISO = p.getDate() != null ? p.getDate().toString() : null;
        d.amount = p.getAmount();
        d.method = p.getMethod();
        d.reference = p.getReference();
        d.notes = p.getNotes();
        d.createdAt = p.getCreatedAt() != null ? p.getCreatedAt().toString() : null;
        return d;
    }




}