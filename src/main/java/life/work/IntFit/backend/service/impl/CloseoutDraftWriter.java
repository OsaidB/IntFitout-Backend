package life.work.IntFit.backend.service.impl;

import life.work.IntFit.backend.model.entity.CloseoutDraft;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.repository.CloseoutDraftRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * The transactional edge of closeout draft writes.
 *
 * Why this exists as its own bean: when an optimistic-lock check or a unique-key check
 * fails, the persistence context is finished. Hibernate says so plainly - "don't flush the
 * Session after an exception occurs" - and a transaction whose flush failed is doomed
 * regardless. The caller still needs to read the draft that won in order to describe the
 * conflict, and that read has to happen in a NEW, clean transaction.
 *
 * So each method here runs in its own transaction and is allowed to fail. The service above
 * catches the failure after this transaction has fully rolled back and closed, then calls
 * {@link #readCurrent} on a fresh one. Doing the catch-and-reread inside a single
 * transaction is what produced raw Hibernate assertion failures instead of a clean 409.
 */
@Component
@RequiredArgsConstructor
public class CloseoutDraftWriter {

    private final CloseoutDraftRepository drafts;

    /**
     * Insert the first draft for a worksite.
     *
     * Races on the unique key over master_worksite_id, so a simultaneous create fails here
     * rather than producing two drafts.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CloseoutDraft create(MasterWorksite master, String json) {
        CloseoutDraft fresh = CloseoutDraft.builder()
                .masterWorksite(master)
                .draftJson(json)
                .build();
        return drafts.saveAndFlush(fresh);
    }

    /**
     * Replace the stored draft, but only if it is still at {@code expectedRevision}.
     *
     * The revision is re-read inside this transaction, so a change committed between the
     * caller's check and this write is caught here. Beyond that, @Version makes the UPDATE
     * itself conditional, which covers the window this check cannot.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CloseoutDraft update(Long masterWorksiteId, String expectedRevision, String json) {
        CloseoutDraft entity = drafts.findByMasterWorksite_Id(masterWorksiteId).orElse(null);
        if (entity == null) return null;                     // deleted meanwhile
        if (!expectedRevision.equals(entity.getId() + ":" + entity.getVersion())) {
            return null;                                     // someone else wrote first
        }
        entity.setDraftJson(json);
        return drafts.saveAndFlush(entity);
    }

    /**
     * Delete the stored draft if it is still at {@code expectedRevision}.
     *
     * @return true if it was deleted, false if it was already gone or had moved on.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean delete(Long masterWorksiteId, String expectedRevision) {
        CloseoutDraft entity = drafts.findByMasterWorksite_Id(masterWorksiteId).orElse(null);
        if (entity == null) return false;
        if (!expectedRevision.equals(entity.getId() + ":" + entity.getVersion())) return false;

        // Deleting the managed entity carries its version into the statement, so a writer
        // that advanced the row in the meantime makes this fail rather than discarding work.
        drafts.delete(entity);
        drafts.flush();
        return true;
    }

    /** Read the stored draft on a clean transaction, for describing a conflict. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<CloseoutDraft> readCurrent(Long masterWorksiteId) {
        return drafts.findByMasterWorksite_Id(masterWorksiteId);
    }
}
