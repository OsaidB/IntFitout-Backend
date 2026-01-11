package life.work.IntFit.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import life.work.IntFit.backend.dto.CloseoutDraftDTO;
import life.work.IntFit.backend.service.CloseoutDraftService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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

    @PutMapping("/draft")
    public ResponseEntity<CloseoutDraftDTO> upsertDraft(
            @PathVariable("masterId") Long masterId,
            @RequestBody JsonNode draft
    ) {
        CloseoutDraftDTO saved = closeoutDraftService.upsertDraft(masterId, draft);
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/draft")
    public ResponseEntity<Void> deleteDraft(@PathVariable("masterId") Long masterId) {
        closeoutDraftService.deleteDraft(masterId);
        return ResponseEntity.noContent().build();
    }
}
