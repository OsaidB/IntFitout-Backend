// StatusMessageController.java
package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.InvoiceDTO;
import life.work.IntFit.backend.model.entity.StatusMessage;
import life.work.IntFit.backend.model.entity.Worksite;
import life.work.IntFit.backend.model.enums.StatusType;
import life.work.IntFit.backend.repository.StatusMessageRepository;
import life.work.IntFit.backend.repository.WorksiteRepository;
import life.work.IntFit.backend.service.InvoiceService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/status-messages")
@CrossOrigin("*")
public class StatusMessageController {

    private final StatusMessageRepository statusMessageRepository;
    private final WorksiteRepository worksiteRepository;
    private final InvoiceService invoiceService;

    public StatusMessageController(
            StatusMessageRepository statusMessageRepository,
            WorksiteRepository worksiteRepository,
            InvoiceService invoiceService
    ) {
        this.statusMessageRepository = statusMessageRepository;
        this.worksiteRepository = worksiteRepository;
        this.invoiceService = invoiceService;
    }

    // =========================
    // READ
    // =========================

    // Get all (eager-load worksite; newest first)
    @GetMapping
    public ResponseEntity<List<StatusMessage>> getAllMessages() {
        return ResponseEntity.ok(statusMessageRepository.findAllWithWorksiteOrderByReceivedAtDesc());
    }

    // Latest saved date (ISO or "")
// Latest saved date (ISO string) or "" if none
    @GetMapping("/latest-saved-date")
    public ResponseEntity<String> getLatestSavedDate() {
        return statusMessageRepository.findTopByOrderByReceivedAtDesc()
                .map(m -> m.getReceivedAt().toString()) // Optional<String>
                .map(ResponseEntity::ok)                 // Optional<ResponseEntity<String>>
                .orElseGet(() -> ResponseEntity.ok("")); // ResponseEntity<String>
    }


    // Latest 20
    @GetMapping("/latest-20")
    public ResponseEntity<List<StatusMessage>> getLatest20() {
        return ResponseEntity.ok(statusMessageRepository.findTop20ByOrderByReceivedAtDesc());
    }

    // Unassigned only
    @GetMapping("/unassigned")
    public ResponseEntity<List<StatusMessage>> getUnassigned() {
        return ResponseEntity.ok(statusMessageRepository.findUnassignedOrderByReceivedAtDesc());
    }

    // By worksite
    @GetMapping("/by-worksite/{worksiteId}")
    public ResponseEntity<List<StatusMessage>> getByWorksite(@PathVariable Long worksiteId) {
        return ResponseEntity.ok(statusMessageRepository.findByWorksite_IdOrderByReceivedAtDesc(worksiteId));
    }

    // =========================
    // ASSIGN / UNASSIGN
    // =========================

    @PatchMapping("/{id}/assign/{worksiteId}")
    public ResponseEntity<StatusMessage> assignWorksite(
            @PathVariable Long id,
            @PathVariable Long worksiteId
    ) {
        StatusMessage sm = statusMessageRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("StatusMessage not found"));

        Worksite ws = worksiteRepository.findById(worksiteId)
                .orElseThrow(() -> new NoSuchElementException("Worksite not found"));

        sm.setWorksite(ws);
        return ResponseEntity.ok(statusMessageRepository.save(sm));
    }

    @PatchMapping("/{id}/unassign")
    public ResponseEntity<StatusMessage> unassignWorksite(@PathVariable Long id) {
        StatusMessage sm = statusMessageRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("StatusMessage not found"));
        sm.setWorksite(null);
        return ResponseEntity.ok(statusMessageRepository.save(sm));
    }

    // =========================
    // APPLY as NEGATIVE INVOICE
    // =========================
    // Converts a RETURN message into a negative invoice that reduces expenses on the chosen worksite.
    // If amount was parsed as positive by older code, we flip it to negative for safety.

    public static record ApplyRequest(
            Long worksiteId,
            String note,
            Boolean useBalanceDate,     // optional: if BALANCE_AT_DATE, use that day @ 12:00
            Boolean forceNegative       // optional safety: force amount negative
    ) {}

    @Transactional
    @PostMapping("/{id}/apply")
    public ResponseEntity<?> applyMessageAsAdjustment(
            @PathVariable Long id,
            @RequestBody ApplyRequest body
    ) {
        StatusMessage sm = statusMessageRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("StatusMessage not found"));

        if (sm.getAppliedInvoiceId() != null) {
            return ResponseEntity.badRequest().body("Message already applied to invoice id " + sm.getAppliedInvoiceId());
        }
        if (body == null || body.worksiteId() == null) {
            return ResponseEntity.badRequest().body("worksiteId is required");
        }

        Worksite ws = worksiteRepository.findById(body.worksiteId())
                .orElseThrow(() -> new NoSuchElementException("Worksite not found"));

        // Only RETURN is supported for expense reduction
        if (sm.getStatusType() != StatusType.RETURN) {
            return ResponseEntity.badRequest().body("Only RETURN messages can be applied as negative invoice adjustments.");
        }

        Double amt = sm.getAmount();
        if (amt == null) {
            return ResponseEntity.badRequest().body("Message has no amount to apply.");
        }
        // Safety: ensure negative
        if (Boolean.TRUE.equals(body.forceNegative) && amt > 0) {
            amt = -amt;
        }
        if (amt > 0) {
            // Even if forceNegative not set, we don't allow a positive 'reduction'
            amt = -Math.abs(amt);
        }

        // Choose date: receivedAt by default; optionally balanceDate@12:00 if requested
        LocalDateTime applyWhen = sm.getReceivedAt();
        if (Boolean.TRUE.equals(body.useBalanceDate) && sm.getBalanceDate() != null) {
            applyWhen = sm.getBalanceDate().atTime(12, 0); // avoid 00:00 edge
        }

        // Build a minimal negative invoice (no items required)
        InvoiceDTO dto = new InvoiceDTO();
        dto.setDate(applyWhen);
        dto.setNetTotal(amt);
        dto.setTotal(amt);
        dto.setTotal_match(Boolean.TRUE);
        dto.setPdfUrl("status-adjustment:" + sm.getId()); // traceable marker
        dto.setReprocessedFromId(null);
        dto.setWorksiteId(ws.getId());
        dto.setWorksiteName(ws.getName());
        dto.setItems(Collections.emptyList());

        InvoiceDTO saved = invoiceService.saveInvoice(dto);

        // Mark message as applied
        sm.setWorksite(ws);
        sm.setAppliedInvoiceId(saved.getId());
        sm.setAppliedAt(LocalDateTime.now());
        sm.setAppliedAmount(amt);
        sm.setAppliedNote(Optional.ofNullable(body.note()).orElse(""));
        statusMessageRepository.save(sm);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("appliedInvoiceId", saved.getId());
        payload.put("appliedAmount", amt);
        payload.put("appliedAt", sm.getAppliedAt().toString());
        payload.put("worksiteId", ws.getId());
        payload.put("worksiteName", ws.getName());

        return ResponseEntity.ok(payload);
    }

    // =========================
    // DELETE
    // =========================
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStatusMessage(@PathVariable Long id) {
        if (!statusMessageRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        statusMessageRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }


    /**
     * Messages since the latest BALANCE_AT_DATE message.
     *
     * Returns:
     * - the latest BALANCE_AT_DATE message
     * - plus all messages received AFTER it
     * ordered newest first.
     *
     * If no BALANCE_AT_DATE exists yet, it falls back to the latest 20 messages.
     */
    @GetMapping("/since-latest-balance")
    public ResponseEntity<List<StatusMessage>> getSinceLatestBalance() {
        return statusMessageRepository
                .findTopByStatusTypeOrderByReceivedAtDesc(StatusType.BALANCE_AT_DATE)
                .map(balanceMsg -> {
                    var messages = statusMessageRepository
                            .findSinceAnchorWithWorksite(balanceMsg.getReceivedAt());
                    return ResponseEntity.ok(messages);
                })
                .orElseGet(() -> {
                    var latest = statusMessageRepository.findTop20ByOrderByReceivedAtDesc();
                    return ResponseEntity.ok(latest);
                });
    }



}
