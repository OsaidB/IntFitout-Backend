package life.work.IntFit.backend.controller;
import life.work.IntFit.backend.service.WorkAssignmentService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
class WorkAssignmentRevisionControllerTest {
    @Test void staleOrMissingRevisionIsAVisibleConflict() throws Exception {
        var service=mock(WorkAssignmentService.class);
        doThrow(new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,"Assignment day changed. Refresh and review your proposal.")).when(service).saveAssignmentsForDate(any());
        MockMvcBuilders.standaloneSetup(new WorkAssignmentController(service)).build()
            .perform(post("/api/assignments").contentType("application/json").content("{\"date\":\"2026-10-22\",\"assignments\":[],\"overtime\":[]}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value("Assignment day changed. Refresh and review your proposal."));
    }
}
