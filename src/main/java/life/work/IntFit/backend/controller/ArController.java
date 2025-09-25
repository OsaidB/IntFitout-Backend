// File: life/work/IntFit/backend/controller/ArController.java
package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.AllocateRequestDTO;
import life.work.IntFit.backend.dto.StatementDTO;
import life.work.IntFit.backend.dto.StatementInvoiceDTO;
import life.work.IntFit.backend.service.ArService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import life.work.IntFit.backend.dto.ArPaymentDTO;
import life.work.IntFit.backend.dto.CreatePaymentDTO;

@RestController
@RequestMapping("/api/ar")
@CrossOrigin("*")
public class ArController {
    private final ArService arService;
    public ArController(ArService arService) { this.arService = arService; }

    @GetMapping("/statement")
    public StatementDTO statement(
            @RequestParam Long masterWorksiteId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        return arService.getStatement(masterWorksiteId, from, to);
    }

    @GetMapping("/open-invoices")
    public List<StatementInvoiceDTO> openInvoices(
            @RequestParam Long masterWorksiteId,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf
    ) {
        return arService.getOpenInvoices(masterWorksiteId, asOf);
    }

    @PostMapping("/payments/{paymentId}/allocate")
    public void allocate(@PathVariable Long paymentId, @RequestBody AllocateRequestDTO body) {
        arService.allocatePayment(paymentId, body);
    }

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



}
