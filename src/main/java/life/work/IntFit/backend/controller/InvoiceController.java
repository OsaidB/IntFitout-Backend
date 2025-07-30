package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.InvoiceDTO;
import life.work.IntFit.backend.dto.PendingInvoiceDTO;
import life.work.IntFit.backend.dto.SmsMessageDTO;
import life.work.IntFit.backend.service.InvoiceService;
import life.work.IntFit.backend.service.PendingInvoiceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/invoices")
@CrossOrigin("*")
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final PendingInvoiceService pendingInvoiceService;

    public InvoiceController(InvoiceService invoiceService, PendingInvoiceService pendingInvoiceService) {
        this.invoiceService = invoiceService;
        this.pendingInvoiceService = pendingInvoiceService;
    }

    // ✅ Normal Invoice Endpoints

    @PostMapping
    public ResponseEntity<InvoiceDTO> createInvoice(@RequestBody InvoiceDTO invoiceDTO) {
        InvoiceDTO saved = invoiceService.saveInvoice(invoiceDTO);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/by-worksite/{worksiteId}")
    public ResponseEntity<List<InvoiceDTO>> getInvoicesByWorksite(@PathVariable Long worksiteId) {
        return ResponseEntity.ok(invoiceService.getInvoicesByWorksiteId(worksiteId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvoiceDTO> getInvoiceById(@PathVariable Long id) {
        return invoiceService.getInvoiceById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<InvoiceDTO>> getInvoicesByDate(@RequestParam(name = "date") String date) {
        return ResponseEntity.ok(invoiceService.getInvoicesByDate(date));
    }


    @GetMapping("/recent")
    public ResponseEntity<List<InvoiceDTO>> getLast20Invoices() {
        return ResponseEntity.ok(invoiceService.getLast20Invoices());
    }

    // 🟡 Pending Invoice Endpoints

    @PostMapping("/pending/upload")
    public ResponseEntity<Void> uploadPendingInvoices(@RequestBody List<PendingInvoiceDTO> pendingInvoices) {
        pendingInvoiceService.savePendingInvoices(pendingInvoices);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/pending")
    public ResponseEntity<List<PendingInvoiceDTO>> getAllPendingInvoices() {
        return ResponseEntity.ok(pendingInvoiceService.getAllPendingInvoices());
    }

    @PostMapping("/pending/confirm/{id}")
    public ResponseEntity<InvoiceDTO> confirmPendingInvoice(@PathVariable Long id) {
        return ResponseEntity.ok(pendingInvoiceService.confirmPendingInvoice(id));
    }

    @DeleteMapping("/pending/{id}")
    public ResponseEntity<Void> deletePendingInvoice(@PathVariable Long id) {
        pendingInvoiceService.deletePendingInvoice(id);
        return ResponseEntity.noContent().build();
    }


    @PostMapping("/sms-invoices/upload")
    public ResponseEntity<Void> uploadSmsMessages(@RequestBody List<SmsMessageDTO> messages) {
        pendingInvoiceService.processSmsMessages(messages);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/latest-date")
    public ResponseEntity<String> getLatestInvoiceDate() {
        return invoiceService.getLastSavedInvoiceDate()
                .map(date -> ResponseEntity.ok(date.toString()))
                .orElse(ResponseEntity.noContent().build());
    }

    @GetMapping("/pending/latest-date")
    public ResponseEntity<String> getLatestPendingInvoiceDate() {
        return pendingInvoiceService.getLastPendingInvoiceDate()
                .map(date -> ResponseEntity.ok(date.toString()))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/pending/fix-unmatched")
    public ResponseEntity<Void> fixUnmatchedInvoices() {
        pendingInvoiceService.reprocessUnmatchedInvoices(); // Implement logic inside
        return ResponseEntity.ok().build();
    }



}
