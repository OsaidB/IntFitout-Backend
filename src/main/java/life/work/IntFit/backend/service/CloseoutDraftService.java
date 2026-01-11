package life.work.IntFit.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import life.work.IntFit.backend.dto.CloseoutDraftDTO;

import java.util.Optional;

public interface CloseoutDraftService {
    Optional<CloseoutDraftDTO> getDraft(Long masterWorksiteId);

    CloseoutDraftDTO upsertDraft(Long masterWorksiteId, JsonNode draft);

    void deleteDraft(Long masterWorksiteId);
}
