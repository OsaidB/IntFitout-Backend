package life.work.IntFit.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import life.work.IntFit.backend.dto.CloseoutDraftDTO;
import life.work.IntFit.backend.model.entity.CloseoutDraft;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.repository.CloseoutDraftRepository;
import life.work.IntFit.backend.repository.MasterWorksiteRepository;
import life.work.IntFit.backend.service.CloseoutDraftService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CloseoutDraftServiceImpl implements CloseoutDraftService {

    private final CloseoutDraftRepository closeoutDraftRepository;
    private final MasterWorksiteRepository masterWorksiteRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public Optional<CloseoutDraftDTO> getDraft(Long masterWorksiteId) {
        return closeoutDraftRepository.findByMasterWorksite_Id(masterWorksiteId)
                .map(this::toDtoSafe);
    }

    @Override
    @Transactional
    public CloseoutDraftDTO upsertDraft(Long masterWorksiteId, JsonNode draft) {
        if (draft == null) {
            throw new IllegalArgumentException("Draft body is required");
        }

        MasterWorksite master = masterWorksiteRepository.findById(masterWorksiteId)
                .orElseThrow(() -> new EntityNotFoundException("MasterWorksite not found: " + masterWorksiteId));

        CloseoutDraft entity = closeoutDraftRepository.findByMasterWorksite_Id(masterWorksiteId)
                .orElseGet(() -> CloseoutDraft.builder()
                        .masterWorksite(master)
                        .draftJson("{}")
                        .build());

        try {
            entity.setDraftJson(objectMapper.writeValueAsString(draft));
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize draft JSON", e);
        }

        CloseoutDraft saved = closeoutDraftRepository.save(entity);
        return toDtoSafe(saved);
    }

    @Override
    @Transactional
    public void deleteDraft(Long masterWorksiteId) {
        closeoutDraftRepository.deleteByMasterWorksite_Id(masterWorksiteId);
    }

    private CloseoutDraftDTO toDtoSafe(CloseoutDraft e) {
        JsonNode node;
        try {
            node = objectMapper.readTree(e.getDraftJson() == null ? "{}" : e.getDraftJson());
        } catch (Exception ex) {
            // If DB got corrupted JSON somehow, don't crash the app
            node = objectMapper.createObjectNode();
        }

        return CloseoutDraftDTO.builder()
                .id(e.getId())
                .masterWorksiteId(e.getMasterWorksite().getId())
                .draft(node)
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
