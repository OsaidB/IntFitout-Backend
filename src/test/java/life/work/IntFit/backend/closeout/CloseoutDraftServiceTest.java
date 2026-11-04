package life.work.IntFit.backend.closeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import life.work.IntFit.backend.dto.CloseoutDraftDTO;
import life.work.IntFit.backend.model.entity.CloseoutDraft;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.repository.CloseoutDraftRepository;
import life.work.IntFit.backend.repository.MasterWorksiteRepository;
import life.work.IntFit.backend.service.CloseoutDraftService;
import life.work.IntFit.backend.service.impl.CloseoutDraftServiceImpl;
import life.work.IntFit.backend.service.impl.CloseoutDraftWriter;
import life.work.IntFit.backend.service.impl.CloseoutDraftWriter.WriteResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The decisions the draft service makes around the database checks: who is refused, what
 * the refusal carries, and that a refused call writes nothing.
 *
 * The guarantees themselves - that two racing writers cannot both land, and that a failure
 * raised at flush or commit still surfaces as a conflict - are established against a real
 * MySQL server in {@link CloseoutDraftServiceMySqlIntegrationTest}, because only a real
 * engine and a real transaction can demonstrate them. These tests cover the branches around
 * that, where a mock is adequate.
 */
class CloseoutDraftServiceTest {

    private static final long MASTER_ID = 7L;
    private static final String NEW_DRAFT = "-1";

    private CloseoutDraftRepository drafts;
    private CloseoutDraftWriter writer;
    private CloseoutDraftServiceImpl service;
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode body(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CloseoutDraft stored(long id, String json, long version) {
        MasterWorksite master = new MasterWorksite();
        master.setId(MASTER_ID);
        CloseoutDraft d = CloseoutDraft.builder()
                .id(id)
                .masterWorksite(master)
                .draftJson(json)
                .build();
        d.setVersion(version);
        return d;
    }

    @BeforeEach
    void setUp() {
        drafts = mock(CloseoutDraftRepository.class);
        writer = mock(CloseoutDraftWriter.class);
        MasterWorksiteRepository masters = mock(MasterWorksiteRepository.class);
        MasterWorksite master = new MasterWorksite();
        master.setId(MASTER_ID);
        when(masters.findById(MASTER_ID)).thenReturn(Optional.of(master));
        service = new CloseoutDraftServiceImpl(drafts, masters, mapper, writer);
    }

    // ----------------------------------------------------------------- refusals

    @Test
    @DisplayName("a client that sends no revision is refused, and nothing is written")
    void missingRevisionIsRefused() {
        CloseoutDraftService.DraftVersionRequiredException e = assertThrows(
                CloseoutDraftService.DraftVersionRequiredException.class,
                () -> service.upsertDraft(MASTER_ID, body("{\"types\":[]}"), null));

        assertTrue(e.getMessage().toLowerCase().contains("update"),
                "the message must tell the user to update the app: " + e.getMessage());
        // The whole point: an older build keeps no unrestricted overwrite path.
        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("a blank revision counts as sending none")
    void blankRevisionIsRefused() {
        assertThrows(CloseoutDraftService.DraftVersionRequiredException.class,
                () -> service.upsertDraft(MASTER_ID, body("{\"types\":[]}"), "   "));
        verifyNoInteractions(writer);
    }

    @Test
    @DisplayName("deleting without a revision is refused too")
    void deleteWithoutRevisionIsRefused() {
        assertThrows(CloseoutDraftService.DraftVersionRequiredException.class,
                () -> service.deleteDraft(MASTER_ID, null));
        verifyNoInteractions(writer);
    }

    // ----------------------------------------------------------------- accepted

    @Test
    @DisplayName("editing the stored revision is accepted and returns the next token")
    void matchingRevisionIsAccepted() {
        when(writer.update(eq(MASTER_ID), eq("1:4"), any(), anyBoolean()))
                .thenReturn(new WriteResult(stored(1L, "{\"types\":[1]}", 5L), java.util.List.of()));

        CloseoutDraftDTO dto = service.upsertDraft(MASTER_ID, body("{\"types\":[1]}"), "1:4");

        assertEquals(5L, dto.getVersion());
        assertEquals("1:5", dto.getRevision(), "the client must learn the new token");
    }

    @Test
    @DisplayName("creating the first draft is accepted on the new-draft sentinel")
    void firstCreateUsesSentinel() {
        when(writer.create(any(), any(), anyBoolean())).thenReturn(new WriteResult(stored(3L, "{\"types\":[]}", 0L), java.util.List.of()));

        CloseoutDraftDTO dto = service.upsertDraft(MASTER_ID, body("{\"types\":[]}"), NEW_DRAFT);

        assertEquals("3:0", dto.getRevision(), "a fresh draft starts at counter 0");
        verify(writer).create(any(), any(), anyBoolean());
        verify(writer, never()).update(anyLong(), anyString(), any(), anyBoolean());
    }

    // ----------------------------------------------------------------- conflicts

    @Test
    @DisplayName("a stale revision is refused and the stored draft travels with the refusal")
    void staleRevisionIsRefused() {
        // The writer reports no match, and the current draft is then read cleanly.
        when(writer.update(eq(MASTER_ID), eq("1:4"), any(), anyBoolean())).thenReturn(null);
        when(writer.readCurrent(MASTER_ID)).thenReturn(Optional.of(stored(1L, "{\"types\":[\"theirs\"]}", 9L)));

        CloseoutDraftService.DraftConflictException e = assertThrows(
                CloseoutDraftService.DraftConflictException.class,
                () -> service.upsertDraft(MASTER_ID, body("{\"types\":[\"mine\"]}"), "1:4"));

        assertNotNull(e.current, "the client needs the stored draft to inspect");
        assertEquals("1:9", e.current.getRevision());
        assertTrue(e.current.getDraft().toString().contains("theirs"));
    }

    @Test
    @DisplayName("a revision from a deleted-and-recreated draft is refused")
    void revisionFromAnotherDraftRowIsRefused() {
        // The replacement row restarted its counter at 0, so the counter alone would make
        // the old "1:0" look current. The row identity is what refuses it.
        when(writer.update(eq(MASTER_ID), eq("1:0"), any(), anyBoolean())).thenReturn(null);
        when(writer.readCurrent(MASTER_ID))
                .thenReturn(Optional.of(stored(7L, "{\"types\":[\"replacement\"]}", 0L)));

        CloseoutDraftService.DraftConflictException e = assertThrows(
                CloseoutDraftService.DraftConflictException.class,
                () -> service.upsertDraft(MASTER_ID, body("{\"types\":[\"stale\"]}"), "1:0"));

        assertEquals("7:0", e.current.getRevision());
    }

    @Test
    @DisplayName("an optimistic-lock failure becomes a conflict, not a crash")
    void optimisticLockFailureBecomesConflict() {
        when(writer.update(eq(MASTER_ID), anyString(), any(), anyBoolean()))
                .thenThrow(new ObjectOptimisticLockingFailureException(CloseoutDraft.class, 1L));
        when(writer.readCurrent(MASTER_ID)).thenReturn(Optional.of(stored(1L, "{\"types\":[]}", 3L)));

        CloseoutDraftService.DraftConflictException e = assertThrows(
                CloseoutDraftService.DraftConflictException.class,
                () -> service.upsertDraft(MASTER_ID, body("{\"types\":[1]}"), "1:2"));
        assertEquals("1:3", e.current.getRevision());
    }

    @Test
    @DisplayName("losing the create race becomes a conflict carrying the winner's draft")
    void uniqueKeyRaceBecomesConflict() {
        when(writer.create(any(), any(), anyBoolean()))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        when(writer.readCurrent(MASTER_ID)).thenReturn(Optional.of(stored(4L, "{\"types\":[\"theirs\"]}", 0L)));

        CloseoutDraftService.DraftConflictException e = assertThrows(
                CloseoutDraftService.DraftConflictException.class,
                () -> service.upsertDraft(MASTER_ID, body("{\"types\":[\"mine\"]}"), NEW_DRAFT));

        assertTrue(e.getMessage().contains("created this closeout first"));
        assertEquals("4:0", e.current.getRevision());
    }

    @Test
    @DisplayName("saving a revision for a draft the server no longer has is refused")
    void updateOfMissingDraftIsRefused() {
        when(writer.update(eq(MASTER_ID), anyString(), any(), anyBoolean())).thenReturn(null);
        when(writer.readCurrent(MASTER_ID)).thenReturn(Optional.empty());

        CloseoutDraftService.DraftConflictException e = assertThrows(
                CloseoutDraftService.DraftConflictException.class,
                () -> service.upsertDraft(MASTER_ID, body("{\"types\":[]}"), "1:4"));
        assertTrue(e.getMessage().contains("no longer exists"));
    }

    // ----------------------------------------------------------------- delete

    @Test
    @DisplayName("a stale delete is refused and the draft is reported as it stands")
    void staleDeleteIsRefused() {
        when(writer.delete(MASTER_ID, "1:0")).thenReturn(false);
        when(writer.readCurrent(MASTER_ID)).thenReturn(Optional.of(stored(1L, "{\"types\":[1]}", 2L)));

        CloseoutDraftService.DraftConflictException e = assertThrows(
                CloseoutDraftService.DraftConflictException.class,
                () -> service.deleteDraft(MASTER_ID, "1:0"));
        assertEquals("1:2", e.current.getRevision());
    }

    @Test
    @DisplayName("deleting a draft that is already gone is not an error")
    void deleteOfMissingDraftIsQuiet() {
        when(writer.delete(MASTER_ID, "1:0")).thenReturn(false);
        when(writer.readCurrent(MASTER_ID)).thenReturn(Optional.empty());

        service.deleteDraft(MASTER_ID, "1:0"); // no throw
    }

    // ----------------------------------------------------------------- reading

    @Test
    @DisplayName("reading a draft reports the token the client must send back")
    void getDraftCarriesRevision() {
        when(drafts.findByMasterWorksite_Id(MASTER_ID))
                .thenReturn(Optional.of(stored(1L, "{\"a\":1}", 6L)));

        CloseoutDraftDTO dto = service.getDraft(MASTER_ID).orElseThrow();
        assertEquals(6L, dto.getVersion());
        assertEquals("1:6", dto.getRevision(), "the token names the row as well as the counter");
    }

    @Test
    @DisplayName("the draft JSON is stored verbatim: no field is dropped or reinterpreted")
    void unknownFieldsSurvive() {
        // Fields the next batch will add (seq, floor) must survive a round trip now, so
        // numbering and floor metadata are not silently lost by an older server.
        String json = "{\"types\":[{\"id\":\"t1\",\"rows\":[{\"id\":\"r1\",\"seq\":12,\"floor\":\"2\"}]}]}";
        when(writer.update(eq(MASTER_ID), eq("1:0"), any(), anyBoolean()))
                .thenAnswer(inv -> new WriteResult(stored(1L, inv.getArgument(2).toString(), 1L), java.util.List.of()));

        CloseoutDraftDTO dto = service.upsertDraft(MASTER_ID, body(json), "1:0");

        String round = dto.getDraft().toString();
        assertTrue(round.contains("\"seq\":12"), "seq must survive: " + round);
        assertTrue(round.contains("\"floor\":\"2\""), "floor must survive: " + round);
    }
}
