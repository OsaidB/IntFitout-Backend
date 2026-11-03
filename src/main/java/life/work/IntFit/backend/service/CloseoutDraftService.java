package life.work.IntFit.backend.service;

import com.fasterxml.jackson.databind.JsonNode;
import life.work.IntFit.backend.dto.CloseoutDraftDTO;

import java.util.Optional;

public interface CloseoutDraftService {
    Optional<CloseoutDraftDTO> getDraft(Long masterWorksiteId);

    /**
     * @param baseRevision the revision token the caller was editing, "<draftId>:<version>",
     *                     or "-1" for "no draft yet". Null is refused, so a client that
     *                     predates revisions cannot overwrite work it never saw.
     */
    CloseoutDraftDTO upsertDraft(Long masterWorksiteId, JsonNode draft, String baseRevision);

    /** The caller sent no revision at all. */
    class DraftVersionRequiredException extends RuntimeException {
        public DraftVersionRequiredException(String message) { super(message); }
    }

    /** Someone else wrote first. Carries the stored draft so the client can show it. */
    class DraftConflictException extends RuntimeException {
        public final transient CloseoutDraftDTO current;
        public DraftConflictException(String message, CloseoutDraftDTO current) {
            super(message);
            this.current = current;
        }
    }

    /**
     * Delete the draft, but only at the revision the caller is holding.
     *
     * Deleting a draft discards every room, type and measurement in it, so it is guarded
     * exactly like a save: a caller whose revision is stale is refused rather than allowed
     * to wipe work it has never seen. {@code null} means the caller sent no revision and is
     * refused outright.
     */
    void deleteDraft(Long masterWorksiteId, String baseRevision);
}
