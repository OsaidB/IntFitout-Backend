package life.work.IntFit.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import life.work.IntFit.backend.dto.CloseoutFinalizeResponseDTO;
import life.work.IntFit.backend.service.CloseoutFinalizeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/master-worksites")
@RequiredArgsConstructor
public class CloseoutFinalizeController {

    private final CloseoutFinalizeService closeoutFinalizeService;

    // Use saved draft by default (no body)
    // OR pass a draft payload in body to finalize that exact version.
    @PostMapping("/{id}/closeout/finalize")
    public CloseoutFinalizeResponseDTO finalizeCloseout(
            @PathVariable("id") Long masterWorksiteId,
            @RequestBody(required = false) JsonNode draftOverride
    ) {
        return closeoutFinalizeService.finalizeCloseout(masterWorksiteId, draftOverride);
    }
}
