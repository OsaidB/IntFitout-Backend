// File: life/work/IntFit/backend/controller/ArController.java
package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.billing.AR.AllocateRequestDTO;
import life.work.IntFit.backend.dto.billing.AR.ArPaymentDTO;
import life.work.IntFit.backend.dto.billing.AR.CreatePaymentDTO;
import life.work.IntFit.backend.dto.billing.AR.CreateChargeDTO;
import life.work.IntFit.backend.dto.billing.AR.UpdateChargeDTO;
import life.work.IntFit.backend.dto.billing.AR.StatementDTO;
import life.work.IntFit.backend.dto.billing.AR.StatementInvoiceDTO;
import life.work.IntFit.backend.dto.billing.AR.StatementChargeDTO;
import life.work.IntFit.backend.service.ArService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/ar")
@CrossOrigin("*")
public class ArController {

    private final ArService arService;

    public ArController(ArService arService) {
        this.arService = arService;
    }

    // =========================
    // Statement (charges + payments)
    // 'to' is inclusive
    // =========================
    @GetMapping("/statement")
    public StatementDTO statement(
            @RequestParam Long masterWorksiteId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return arService.getStatement(masterWorksiteId, from, to);
    }

    // =========================
    // Compatibility: "open-invoices"
    // (returns open CHARGES using the old invoice-shaped DTO)
    // =========================
    @GetMapping("/open-invoices")
    public List<StatementInvoiceDTO> openInvoices(
            @RequestParam Long masterWorksiteId,
            @RequestParam(name = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
    ) {
        return arService.getOpenInvoices(masterWorksiteId, asOf);
    }

    // =========================
    // Payments
    // =========================
    @PostMapping("/payments")
    public ArPaymentDTO createPayment(@RequestBody CreatePaymentDTO body) {
        return arService.createPayment(body);
    }

    @GetMapping("/payments")
    public List<ArPaymentDTO> listPayments(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long masterWorksiteId
    ) {
        return arService.listPayments(from, to, masterWorksiteId);
    }

    @PostMapping("/payments/{paymentId}/allocate")
    public void allocate(@PathVariable Long paymentId, @RequestBody AllocateRequestDTO body) {
        // NOTE: AllocateRequestDTO.Line.invoiceId is treated as CHARGE ID in the service
        arService.allocatePayment(paymentId, body);
    }

    // =========================
    // Charges (CRUD)
    // =========================
    @PostMapping("/charges")
    public StatementChargeDTO createCharge(@RequestBody CreateChargeDTO body) {
        return arService.createCharge(
                body.masterWorksiteId,
                body.dateISO,
                body.description,
                body.amount
        );
    }

    @PatchMapping("/charges/{id}")
    public void updateCharge(@PathVariable Long id, @RequestBody UpdateChargeDTO body) {
        arService.updateCharge(
                id,
                body.dateISO,
                body.description,
                body.amount
        );
    }

    @DeleteMapping("/charges/{id}")
    public void deleteCharge(@PathVariable Long id) {
        arService.deleteCharge(id);
    }

    // Optional convenience: list charges in a range (reuses statement -> includes paid/remaining)
    @GetMapping("/charges")
    public List<StatementChargeDTO> listCharges(
            @RequestParam Long masterWorksiteId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return arService.getStatement(masterWorksiteId, from, to).charges;
    }
}
