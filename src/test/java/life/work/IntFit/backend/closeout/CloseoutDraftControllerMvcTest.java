package life.work.IntFit.backend.closeout;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import life.work.IntFit.backend.controller.CloseoutDraftController;
import life.work.IntFit.backend.dto.CloseoutDraftDTO;
import life.work.IntFit.backend.service.CloseoutDraftService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP boundary.
 *
 * {@link CloseoutDraftServiceMySqlIntegrationTest} proves the service refuses a racing or
 * stale writer; this proves those refusals reach the client as the statuses the app is
 * written against. Without it the service could be correct while every conflict arrived as a
 * 500 and the app treated it as a network error - which would quietly reintroduce the
 * overwrite it exists to prevent.
 *
 *   200  saved, body carries the new revision token
 *   409  someone else wrote first, body carries the stored draft
 *   428  no revision supplied: this client is too old to save safely
 */
class CloseoutDraftControllerMvcTest {

    private static final long MASTER_ID = 42L;

    private CloseoutDraftService service;
    private MockMvc mvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode node(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CloseoutDraftDTO dto(String revision, long version, String draftJson) {
        return CloseoutDraftDTO.builder()
                .id(1L)
                .masterWorksiteId(MASTER_ID)
                .draft(node(draftJson))
                .version(version)
                .revision(revision)
                .build();
    }

    private String url() {
        return "/api/master-worksites/" + MASTER_ID + "/closeout/draft";
    }

    @BeforeEach
    void setUp() {
        service = mock(CloseoutDraftService.class);
        mvc = MockMvcBuilders.standaloneSetup(new CloseoutDraftController(service)).build();
    }

    // ----------------------------------------------------------------- save

    @Test
    @DisplayName("200: an accepted save returns the new revision token")
    void acceptedSaveIs200() throws Exception {
        when(service.upsertDraft(eq(MASTER_ID), any(), eq("1:4")))
                .thenReturn(dto("1:5", 5L, "{\"types\":[]}"));

        mvc.perform(put(url())
                        .header("If-Match", "1:4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draft\":{\"types\":[]},\"baseRevision\":\"1:4\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("1:5"));
    }

    @Test
    @DisplayName("409: a conflict returns the stored draft so the app can show it")
    void conflictIs409WithCurrentDraft() throws Exception {
        when(service.upsertDraft(eq(MASTER_ID), any(), eq("1:4")))
                .thenThrow(new CloseoutDraftService.DraftConflictException(
                        "This closeout changed on another device.",
                        dto("1:9", 9L, "{\"types\":[{\"id\":\"theirs\"}]}")));

        mvc.perform(put(url())
                        .header("If-Match", "1:4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draft\":{\"types\":[]},\"baseRevision\":\"1:4\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAFT_CONFLICT"))
                .andExpect(jsonPath("$.current.revision").value("1:9"))
                // The app needs the measurements themselves, not just a message.
                .andExpect(jsonPath("$.current.draft.types[0].id").value("theirs"));
    }

    @Test
    @DisplayName("428: no revision at all is refused, and nothing is saved")
    void missingRevisionIs428() throws Exception {
        when(service.upsertDraft(eq(MASTER_ID), any(), isNull()))
                .thenThrow(new CloseoutDraftService.DraftVersionRequiredException(
                        "This version of the app cannot save closeouts safely. Please update the app."));

        mvc.perform(put(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"types\":[]}"))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("DRAFT_VERSION_REQUIRED"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("update the app")));
    }

    @Test
    @DisplayName("an old client's bare-draft body reaches the service with no revision")
    void oldClientBodyShapeCarriesNoRevision() throws Exception {
        // The previous app build PUT the draft directly, with no If-Match and no
        // baseRevision. It must arrive as null - the refusable case - and never be guessed.
        when(service.upsertDraft(anyLong(), any(), isNull()))
                .thenThrow(new CloseoutDraftService.DraftVersionRequiredException("update"));

        mvc.perform(put(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"types\":[{\"id\":\"t1\"}],\"updatedAt\":\"2026-01-01T00:00:00Z\"}"))
                .andExpect(status().is(428));

        verify(service).upsertDraft(eq(MASTER_ID), any(), isNull());
    }

    @Test
    @DisplayName("the revision may travel in the body when a client cannot set headers")
    void revisionFromBodyIsAccepted() throws Exception {
        when(service.upsertDraft(eq(MASTER_ID), any(), eq("1:7")))
                .thenReturn(dto("1:8", 8L, "{\"types\":[]}"));

        mvc.perform(put(url())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draft\":{\"types\":[]},\"baseRevision\":\"1:7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("1:8"));
    }

    @Test
    @DisplayName("a quoted ETag revision is unwrapped")
    void quotedIfMatchIsAccepted() throws Exception {
        when(service.upsertDraft(eq(MASTER_ID), any(), eq("1:4")))
                .thenReturn(dto("1:5", 5L, "{\"types\":[]}"));

        mvc.perform(put(url())
                        .header("If-Match", "\"1:4\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draft\":{\"types\":[]}}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("the new-draft sentinel passes through as sent")
    void newDraftSentinelPassesThrough() throws Exception {
        when(service.upsertDraft(eq(MASTER_ID), any(), eq("-1")))
                .thenReturn(dto("3:0", 0L, "{\"types\":[]}"));

        mvc.perform(put(url())
                        .header("If-Match", "-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draft\":{\"types\":[]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("3:0"));
    }

    // ----------------------------------------------------------------- read

    @Test
    @DisplayName("200: reading a draft reports the revision the client must send back")
    void getReturnsRevision() throws Exception {
        when(service.getDraft(MASTER_ID)).thenReturn(Optional.of(dto("1:6", 6L, "{\"types\":[]}")));

        mvc.perform(get(url()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.revision").value("1:6"));
    }

    @Test
    @DisplayName("204: no draft stored yet")
    void getReturns204WhenMissing() throws Exception {
        when(service.getDraft(MASTER_ID)).thenReturn(Optional.empty());

        mvc.perform(get(url())).andExpect(status().isNoContent());
    }

    // ----------------------------------------------------------------- delete

    @Test
    @DisplayName("204: a delete on the current revision succeeds")
    void deleteIs204() throws Exception {
        doNothing().when(service).deleteDraft(MASTER_ID, "1:3");

        mvc.perform(delete(url()).header("If-Match", "1:3"))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("409: a stale delete is refused, with the stored draft")
    void staleDeleteIs409() throws Exception {
        doThrow(new CloseoutDraftService.DraftConflictException(
                "This closeout changed on another device. Reload before deleting.",
                dto("1:9", 9L, "{\"types\":[]}")))
                .when(service).deleteDraft(MASTER_ID, "1:0");

        mvc.perform(delete(url()).header("If-Match", "1:0"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DRAFT_CONFLICT"))
                .andExpect(jsonPath("$.current.revision").value("1:9"));
    }

    @Test
    @DisplayName("428: a delete with no revision is refused")
    void deleteWithoutRevisionIs428() throws Exception {
        doThrow(new CloseoutDraftService.DraftVersionRequiredException("update"))
                .when(service).deleteDraft(eq(MASTER_ID), isNull());

        mvc.perform(delete(url()))
                .andExpect(status().is(428))
                .andExpect(jsonPath("$.code").value("DRAFT_VERSION_REQUIRED"));
    }
}
