package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.ChangeWorksiteDTO;
import life.work.IntFit.backend.dto.InvoiceDTO;
import life.work.IntFit.backend.dto.PendingInvoiceDTO;
import life.work.IntFit.backend.dto.SmsMessageDTO;
import life.work.IntFit.backend.model.entity.FailedInvoiceImport;
import life.work.IntFit.backend.service.InvoiceService;
import life.work.IntFit.backend.service.PendingInvoiceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
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

    // =========================
    // ✅ Normal Invoice Endpoints

    @PostMapping
    public ResponseEntity<?> createInvoice(@RequestBody InvoiceDTO invoiceDTO) {
        if (invoiceDTO == null) {
            System.err.println("❌ Received null InvoiceDTO");
            return ResponseEntity.badRequest().body("Invoice data is missing.");
        }

        InvoiceDTO saved = invoiceService.saveInvoice(invoiceDTO);
        return ResponseEntity.ok(saved);
    }


    // 🔹 NEW: manual invoice created from the app UI
    @PostMapping("/manual")
    public ResponseEntity<?> createManualInvoice(@RequestBody InvoiceDTO invoiceDTO) {
        if (invoiceDTO == null) {
            System.err.println("❌ Received null InvoiceDTO (manual)");
            return ResponseEntity.badRequest().body("Invoice data is missing.");
        }

        InvoiceDTO saved = invoiceService.saveManualInvoice(invoiceDTO);
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

    /**
     * Query invoices by business day (YYYY-MM-DD).
     * Service uses a day window [00:00, next 00:00) so DB keeps actual times.
     */
    @GetMapping
    public ResponseEntity<List<InvoiceDTO>> getInvoicesByDate(@RequestParam(name = "date") String date) {
        return ResponseEntity.ok(invoiceService.getInvoicesByDate(date));
    }


    @GetMapping("/recent")
    public ResponseEntity<List<InvoiceDTO>> getLast20Invoices() {
        return ResponseEntity.ok(invoiceService.getLast20Invoices());
    }

    // --------------------------------------------------------
    // 🔹 NEW: time-aware (fixes the 00:00 issue on the frontend)
    // --------------------------------------------------------
    @GetMapping("/latest-business-datetime")
    public ResponseEntity<String> getLatestInvoiceBusinessDateTime() {
        return invoiceService.getLastSavedInvoiceDateTime()
                .map(LocalDateTime::toString) // e.g., 2025-05-31T08:55:39
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok("")); // frontend treats "" as null
    }

    // ==============================
    // 🟡 Pending Invoice Endpoints

    @PostMapping("/pending/upload")
    public ResponseEntity<?> uploadPendingInvoices(@RequestBody List<PendingInvoiceDTO> pendingInvoices) {
        if (pendingInvoices == null || pendingInvoices.isEmpty()) {
            System.err.println("❌ Received empty pending invoice list");
            return ResponseEntity.badRequest().body("Pending invoice list is empty.");
        }

        pendingInvoiceService.savePendingInvoices(pendingInvoices);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/pending")
    public ResponseEntity<List<PendingInvoiceDTO>> getAllPendingInvoices() {
        return ResponseEntity.ok(pendingInvoiceService.getAllPendingInvoices());
    }

    @PatchMapping("/pending/{id}/confirm")
    public ResponseEntity<Void> confirmPendingInvoice(@PathVariable Long id) {
        pendingInvoiceService.confirmPendingInvoice(id);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/pending/{id}")
    public ResponseEntity<Void> deletePendingInvoice(@PathVariable Long id) {
        pendingInvoiceService.deletePendingInvoice(id);
        return ResponseEntity.noContent().build();
    }


    @PostMapping("/sms-invoices/upload")
    public ResponseEntity<?> uploadSmsMessages(@RequestBody List<SmsMessageDTO> messages) {
        if (messages == null || messages.isEmpty()) {
            System.err.println("❌ Received empty SMS message list");
            return ResponseEntity.badRequest().body("SMS message list is empty.");
        }

        // Returns a per-batch summary so partial failures are visible to the caller/logs.
        // Still HTTP 200 for partial failures (Android treats any non-2xx as an upload failure).
        var summary = pendingInvoiceService.processSmsMessages(messages);
        return ResponseEntity.ok(summary);
    }

    // Review recent invoice SMS imports that failed to become PendingInvoices (newest first).
    @GetMapping("/sms-import-failures")
    public ResponseEntity<List<FailedInvoiceImport>> getSmsImportFailures(
            @RequestParam(name = "limit", defaultValue = "50") int limit
    ) {
        int safeLimit = (limit <= 0 || limit > 500) ? 50 : limit;
        return ResponseEntity.ok(pendingInvoiceService.getRecentFailedInvoiceImports(safeLimit));
    }


//    // --------------------------------------------------------
//    // ⚠️ Legacy: date-only (kept for backward compatibility)
//    // --------------------------------------------------------
//    @Deprecated
//    @GetMapping("/latest-date")
//    public ResponseEntity<String> getLatestInvoiceDate() {
//        return invoiceService.getLastSavedInvoiceDate()
//                .map(Object::toString)         // returns YYYY-MM-DD (date-only)
//                .map(ResponseEntity::ok)
//                .orElseGet(() -> ResponseEntity.ok(""));
//    }

    @GetMapping("/pending/latest-date")
    public ResponseEntity<String> getLatestPendingInvoiceDate() {
        return pendingInvoiceService.getLastPendingInvoiceDate()
                .map(date -> ResponseEntity.ok(date.toString()))
                .orElse(ResponseEntity.noContent().build());
    }

    @PostMapping("/pending/fix-unmatched")
    public ResponseEntity<Void> fixUnmatchedInvoices() {
        pendingInvoiceService.reprocessUnmatchedInvoices();
        return ResponseEntity.ok().build();
    }

    // 🔹 NEW: pending – single latest BUSINESS datetime (keeps time)
    @GetMapping("/pending/latest-business-datetime")
    public ResponseEntity<String> getLatestPendingInvoiceBusinessDateTime(
            @RequestParam(name = "onlyUnconfirmed", defaultValue = "true") boolean onlyUnconfirmed
    ) {
        return pendingInvoiceService.getLastPendingInvoiceDateTime(onlyUnconfirmed)
                .map(LocalDateTime::toString)   // e.g. 2025-08-24T08:58:00
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok("")); // frontend treats "" as null
    }


    // --------------------------------------------------------
    // 🔹 NEW: pending – single latest (business LocalDate)
    // --------------------------------------------------------
    @GetMapping("/pending/latest-business-date")
    public ResponseEntity<String> getLatestPendingInvoiceBusinessDate(
            @RequestParam(name = "onlyUnconfirmed", defaultValue = "true") boolean onlyUnconfirmed
    ) {
        return pendingInvoiceService.getLastPendingInvoiceBusinessDate(onlyUnconfirmed)
                .map(Object::toString) // YYYY-MM-DD
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(""));
    }


    // PUT /api/invoices/{id}/worksite   with body: { "worksiteId": 123 }
    @PutMapping("/{id}/worksite")
    public ResponseEntity<InvoiceDTO> changeInvoiceWorksite(
            @PathVariable Long id,
            @RequestBody ChangeWorksiteDTO body
    ) {
        if (body == null || body.getWorksiteId() == null) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(invoiceService.changeInvoiceWorksite(id, body.getWorksiteId()));
    }

    // PUT /api/invoices/{id}/worksite/{worksiteId}
    @PutMapping("/{id}/worksite/{worksiteId}")
    public ResponseEntity<InvoiceDTO> changeInvoiceWorksitePath(
            @PathVariable Long id,
            @PathVariable Long worksiteId
    ) {
        return ResponseEntity.ok(invoiceService.changeInvoiceWorksite(id, worksiteId));
    }

    // ✅ NEW: pending – latest SMS received datetime (the real SMS time)
    @GetMapping("/pending/latest-sms-datetime")
    public ResponseEntity<String> getLatestPendingInvoiceSmsDateTime(
            @RequestParam(name = "onlyUnconfirmed", defaultValue = "false") boolean onlyUnconfirmed
    ) {
        return pendingInvoiceService.getLastPendingInvoiceSmsDateTime(onlyUnconfirmed)
                .map(LocalDateTime::toString)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(""));
    }


}
