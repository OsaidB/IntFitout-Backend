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

import life.work.IntFit.backend.service.CloseoutDraftService.DraftConflictException;
import life.work.IntFit.backend.service.CloseoutDraftService.DraftVersionRequiredException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class CloseoutDraftServiceImpl implements CloseoutDraftService {

    private final CloseoutDraftRepository closeoutDraftRepository;
    private final MasterWorksiteRepository masterWorksiteRepository;
    private final ObjectMapper objectMapper;
    /** Holds the transactional edge; see CloseoutDraftWriter for why it is separate. */
    private final CloseoutDraftWriter writer;

    /** The revision a client sends when it believes no draft is stored yet. */
    private static final String NEW_DRAFT_BASE = "-1";

    /**
     * The token identifying a stored draft: its row id and its counter.
     *
     * Both halves matter. The counter stops a stale edit of this draft; the row id stops a
     * stale edit of a DIFFERENT, since-deleted draft whose counter happens to match the
     * replacement's.
     */
    private static String revisionOf(CloseoutDraft e) {
        return e.getId() + ":" + e.getVersion();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CloseoutDraftDTO> getDraft(Long masterWorksiteId) {
        return closeoutDraftRepository.findByMasterWorksite_Id(masterWorksiteId)
                .map(this::toDtoSafe);
    }

    /**
     * Replace the draft, but only if the caller is editing the revision that is stored.
     *
     * The draft is a single JSON blob covering every room, type and measurement, so an
     * unchecked write replaces another device's work wholesale. Two database-level checks
     * decide it, never a timestamp and never a device clock:
     *
     *   - creating the first draft races on the unique key over master_worksite_id;
     *   - updating races on @Version, which Hibernate compiles into
     *     "UPDATE ... WHERE id = ? AND version = ?", so the loser updates nothing.
     *
     * This method is deliberately NOT transactional. The write happens in its own
     * transaction inside {@link CloseoutDraftWriter}, which is allowed to fail; only once
     * that transaction has rolled back and closed is it safe to read the winning draft for
     * the conflict payload. Doing that read inside the failed transaction is an error -
     * Hibernate refuses to flush a session after an exception, and the result was a raw
     * assertion failure reaching the client instead of a clean 409.
     *
     * A caller that sends no revision is refused outright, so an older app build keeps no
     * unrestricted overwrite path.
     */
    @Override
    public CloseoutDraftDTO upsertDraft(Long masterWorksiteId, JsonNode draft, String baseRevision) {
        if (draft == null) {
            throw new IllegalArgumentException("Draft body is required");
        }
        if (baseRevision == null || baseRevision.isBlank()) {
            throw new DraftVersionRequiredException(
                    "This version of the app cannot save closeouts safely. Please update the app.");
        }

        MasterWorksite master = masterWorksiteRepository.findById(masterWorksiteId)
                .orElseThrow(() -> new EntityNotFoundException("MasterWorksite not found: " + masterWorksiteId));

        String json;
        try {
            json = objectMapper.writeValueAsString(draft);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize draft JSON", e);
        }

        if (NEW_DRAFT_BASE.equals(baseRevision)) {
            try {
                return toDtoSafe(writer.create(master, json));
            } catch (DataIntegrityViolationException race) {
                // The unique key decided: a draft already exists, created either long ago
                // or by another device in this same instant. Read it on a clean transaction.
                throw conflict("Another device created this closeout first.", masterWorksiteId);
            }
        }

        CloseoutDraft saved;
        try {
            saved = writer.update(masterWorksiteId, baseRevision, json);
        } catch (OptimisticLockingFailureException lost) {
            // The conditional UPDATE matched no row: another transaction committed between
            // this one's read and its flush.
            throw conflict("This closeout changed on another device.", masterWorksiteId);
        }

        if (saved == null) {
            // The revision no longer matches, or the draft was deleted. Either way the
            // caller must look before saving again.
            return failStale(masterWorksiteId);
        }
        return toDtoSafe(saved);
    }

    /**
     * Build a conflict carrying whatever is stored now, read on its own clean transaction.
     */
    private DraftConflictException conflict(String message, Long masterWorksiteId) {
        CloseoutDraft current = writer.readCurrent(masterWorksiteId).orElse(null);
        return new DraftConflictException(message, current == null ? null : toDtoSafe(current));
    }

    private CloseoutDraftDTO failStale(Long masterWorksiteId) {
        CloseoutDraft current = writer.readCurrent(masterWorksiteId).orElse(null);
        if (current == null) {
            throw new DraftConflictException(
                    "This closeout no longer exists on the server. Reload before saving.", null);
        }
        throw new DraftConflictException("This closeout changed on another device.", toDtoSafe(current));
    }

    /**
     * Discard the draft, guarded exactly like a save because it destroys every measurement
     * in it. Not transactional, for the same reason as {@link #upsertDraft}.
     */
    @Override
    public void deleteDraft(Long masterWorksiteId, String baseRevision) {
        if (baseRevision == null || baseRevision.isBlank()) {
            throw new DraftVersionRequiredException(
                    "This version of the app cannot delete closeouts safely. Please update the app.");
        }

        boolean deleted;
        try {
            deleted = writer.delete(masterWorksiteId, baseRevision);
        } catch (OptimisticLockingFailureException lost) {
            throw conflict("This closeout changed on another device. Reload before deleting.",
                    masterWorksiteId);
        }

        if (!deleted) {
            CloseoutDraft current = writer.readCurrent(masterWorksiteId).orElse(null);
            if (current == null) {
                // Already gone: nothing to discard and nothing to report.
                return;
            }
            throw new DraftConflictException(
                    "This closeout changed on another device. Reload before deleting.",
                    toDtoSafe(current));
        }
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
                .version(e.getVersion())
                .revision(revisionOf(e))
                .updatedAt(e.getUpdatedAt())
                .build();
    }
}
