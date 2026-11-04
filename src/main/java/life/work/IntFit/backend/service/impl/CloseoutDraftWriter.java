package life.work.IntFit.backend.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import life.work.IntFit.backend.closeout.MeasurementNumbering;
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
    private final ObjectMapper objectMapper;

    /**
     * What a write did, including the measurement numbers the server allocated.
     *
     * The caller needs the saved entity (for the new revision) and the stripped-row report,
     * which is how an older client that drops unknown JSON fields is caught.
     */
    public static final class WriteResult {
        public final CloseoutDraft saved;
        public final java.util.List<String> strippedRowIds;

        public WriteResult(CloseoutDraft saved, java.util.List<String> strippedRowIds) {
            this.saved = saved;
            this.strippedRowIds = strippedRowIds;
        }
    }

    /**
     * Allocate measurement numbers and serialise the draft, inside the caller's transaction.
     *
     * Allocation has to happen here rather than in the service above, because it reads and
     * advances the high-water mark on the row being written. Doing it outside the transaction
     * that holds the revision check would let two writers allocate from the same mark.
     */
    private MeasurementNumbering.Result numberRows(JsonNode incoming, CloseoutDraft existing,
                                                   boolean clientUnderstandsMetadata) {
        JsonNode stored = null;
        long nextSeq = 1L;
        if (existing != null) {
            nextSeq = existing.getNextMeasurementSeq() == null ? 1L : existing.getNextMeasurementSeq();
            try {
                stored = objectMapper.readTree(
                        existing.getDraftJson() == null ? "{}" : existing.getDraftJson());
            } catch (Exception ignored) {
                // Unparsable stored JSON cannot tell us which numbers are in use, so the
                // mark alone governs. It only moves upwards, so nothing is reused.
                stored = null;
            }
        }
        return MeasurementNumbering.allocate(incoming, stored, nextSeq, clientUnderstandsMetadata);
    }

    private String writeJson(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize draft JSON", e);
        }
    }

    /**
     * Insert the first draft for a worksite.
     *
     * Races on the unique key over master_worksite_id, so a simultaneous create fails here
     * rather than producing two drafts.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WriteResult create(MasterWorksite master, JsonNode incoming,
                              boolean clientUnderstandsMetadata) {
        MeasurementNumbering.Result numbered = numberRows(incoming, null, clientUnderstandsMetadata);
        CloseoutDraft fresh = CloseoutDraft.builder()
                .masterWorksite(master)
                .draftJson(writeJson(numbered.draft))
                .build();
        fresh.setNextMeasurementSeq(numbered.nextSeq);
        return new WriteResult(drafts.saveAndFlush(fresh), numbered.strippedRowIds);
    }

    /**
     * Replace the stored draft, but only if it is still at {@code expectedRevision}.
     *
     * The revision is re-read inside this transaction, so a change committed between the
     * caller's check and this write is caught here. Beyond that, @Version makes the UPDATE
     * itself conditional, which covers the window this check cannot.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public WriteResult update(Long masterWorksiteId, String expectedRevision, JsonNode incoming,
                              boolean clientUnderstandsMetadata) {
        CloseoutDraft entity = drafts.findByMasterWorksite_Id(masterWorksiteId).orElse(null);
        if (entity == null) return null;                     // deleted meanwhile
        if (!expectedRevision.equals(entity.getId() + ":" + entity.getVersion())) {
            return null;                                     // someone else wrote first
        }

        MeasurementNumbering.Result numbered = numberRows(incoming, entity, clientUnderstandsMetadata);
        if (!numbered.strippedRowIds.isEmpty()) {
            // Report without writing. The caller turns this into a refusal; letting it
            // through would renumber measurements the user can already see.
            return new WriteResult(null, numbered.strippedRowIds);
        }

        entity.setDraftJson(writeJson(numbered.draft));
        // The mark never goes down, so a number cannot be handed out twice even if the
        // incoming draft has fewer rows than the stored one.
        long current = entity.getNextMeasurementSeq() == null ? 1L : entity.getNextMeasurementSeq();
        entity.setNextMeasurementSeq(Math.max(current, numbered.nextSeq));
        return new WriteResult(drafts.saveAndFlush(entity), java.util.List.of());
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
