package life.work.IntFit.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import life.work.IntFit.backend.dto.CloseoutDraftDTO;
import life.work.IntFit.backend.service.CloseoutDraftService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@CrossOrigin("*") // keep consistent with your current style
@RequestMapping("/api/master-worksites/{masterId}/closeout")
public class CloseoutDraftController {

    private final CloseoutDraftService closeoutDraftService;

    @GetMapping("/draft")
    public ResponseEntity<CloseoutDraftDTO> getDraft(@PathVariable("masterId") Long masterId) {
        return closeoutDraftService.getDraft(masterId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build()); // 204 if missing
    }

    /**
     * Save the draft for a closeout.
     *
     * The revision the client was editing travels in the If-Match header, or as a
     * baseVersion field inside the body for clients that cannot set headers easily.
     * "-1" means "I believe there is no draft yet".
     *
     * Responses:
     *   200 saved, body carries the new revision
     *   409 someone else wrote first; body carries the stored draft to inspect
     *   428 no revision supplied - the caller is too old to save safely
     */
    @PutMapping("/draft")
    public ResponseEntity<?> upsertDraft(
            @PathVariable("masterId") Long masterId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch,
            @RequestHeader(value = "X-Closeout-Metadata", required = false) String metadataHeader,
            @RequestBody JsonNode body
    ) {
        String baseRevision = cleanRevision(ifMatch);
        if (baseRevision == null && body != null && body.hasNonNull("baseRevision")) {
            baseRevision = cleanRevision(body.get("baseRevision").asText());
        }
        JsonNode draft = (body != null && body.has("draft")) ? body.get("draft") : body;

        /*
         * Does this caller round-trip measurement metadata?
         *
         * It has to be declared, not guessed. A build that predates numbering cannot send
         * this, so its saves are still checked for stripped numbers. A build that does send
         * it is reconciled instead: a row arriving without a number the server holds means
         * that client loaded the draft before allocation, which happens legitimately after a
         * conflict is resolved by keeping the local copy.
         */
        boolean clientUnderstandsMetadata = declaresMetadataSupport(metadataHeader, body);

        try {
            return ResponseEntity.ok(closeoutDraftService.upsertDraft(
                    masterId, draft, baseRevision, clientUnderstandsMetadata));
        } catch (CloseoutDraftService.DraftVersionRequiredException e) {
            return ResponseEntity.status(428).body(Map.of(
                    "error", e.getMessage(), "status", 428, "code", "DRAFT_VERSION_REQUIRED"));
        } catch (CloseoutDraftService.DraftMetadataRequiredException e) {
            // Same 428 the client already maps to "please update the app", with a distinct
            // code so the cause is identifiable in logs. Deliberately NOT a 409: a conflict
            // invites the user to choose, and one of those choices would discard the
            // measurement numbers this refusal exists to protect.
            return ResponseEntity.status(428).body(Map.of(
                    "error", e.getMessage(), "status", 428,
                    "code", "DRAFT_METADATA_REQUIRED",
                    "affectedRows", e.rowIds.size()));
        } catch (CloseoutDraftService.DraftConflictException e) {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("error", e.getMessage());
            payload.put("status", 409);
            payload.put("code", "DRAFT_CONFLICT");
            // The stored draft travels with the refusal so the client can show it without
            // a second request that might race again.
            payload.put("current", e.current);
            return ResponseEntity.status(409).body(payload);
        }
    }

    /**
     * True when the caller states it preserves measurement metadata. Accepted either as the
     * X-Closeout-Metadata header or as metadataVersion in the body, because a client that
     * cannot set headers conveniently should not be forced to.
     */
    private static boolean declaresMetadataSupport(String header, JsonNode body) {
        if (header != null && !header.isBlank()) {
            String cleaned = header.trim();
            if (cleaned.equals("1") || cleaned.equalsIgnoreCase("v1")) return true;
        }
        if (body != null && body.hasNonNull("metadataVersion")) {
            JsonNode v = body.get("metadataVersion");
            if (v.isNumber()) return v.asInt() >= 1;
            try {
                return Integer.parseInt(v.asText().trim()) >= 1;
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return false;
    }

    /**
     * Strip ETag quoting. The token is opaque to the controller: only the service knows
     * what a valid revision looks like, and a malformed one must be refused there rather
     * than quietly become null here (null means "sent nothing", which is a different case).
     */
    private static String cleanRevision(String raw) {
        if (raw == null) return null;
        String cleaned = raw.trim().replace("\"", "");
        return cleaned.isEmpty() ? null : cleaned;
    }

    /**
     * Discard a closeout draft. Guarded like a save, because it destroys every measurement
     * in the draft: the revision travels in If-Match, and a stale one is refused.
     */
    @DeleteMapping("/draft")
    public ResponseEntity<?> deleteDraft(
            @PathVariable("masterId") Long masterId,
            @RequestHeader(value = "If-Match", required = false) String ifMatch
    ) {
        try {
            closeoutDraftService.deleteDraft(masterId, cleanRevision(ifMatch));
            return ResponseEntity.noContent().build();
        } catch (CloseoutDraftService.DraftVersionRequiredException e) {
            return ResponseEntity.status(428).body(Map.of(
                    "error", e.getMessage(), "status", 428, "code", "DRAFT_VERSION_REQUIRED"));
        } catch (CloseoutDraftService.DraftConflictException e) {
            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("error", e.getMessage());
            payload.put("status", 409);
            payload.put("code", "DRAFT_CONFLICT");
            payload.put("current", e.current);
            return ResponseEntity.status(409).body(payload);
        }
    }
}
