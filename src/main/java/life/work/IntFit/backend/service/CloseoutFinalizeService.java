package life.work.IntFit.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import life.work.IntFit.backend.dto.CloseoutFinalizeResponseDTO;

public interface CloseoutFinalizeService {
    CloseoutFinalizeResponseDTO finalizeCloseout(Long masterWorksiteId, JsonNode optionalDraftOverride);
}
