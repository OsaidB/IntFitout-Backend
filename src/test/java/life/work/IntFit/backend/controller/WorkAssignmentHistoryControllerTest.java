package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.daily_assignments.AssignmentHistoryDTO;
import life.work.IntFit.backend.service.WorkAssignmentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Range validation for GET /api/assignments/history. The controller must reject
 * an unbounded or inverted range before it reaches the database.
 */
@ExtendWith(MockitoExtension.class)
class WorkAssignmentHistoryControllerTest {

    @Mock
    private WorkAssignmentService service;

    private WorkAssignmentController controller() {
        return new WorkAssignmentController(service);
    }

    @Test
    void acceptsAWholeCalendarMonth() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 31);
        AssignmentHistoryDTO dto = new AssignmentHistoryDTO(from, to, from, List.of());
        when(service.getHistory(from, to)).thenReturn(dto);

        ResponseEntity<AssignmentHistoryDTO> response = controller().getHistory(from, to);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(dto);
    }

    @Test
    void rejectsAnInvertedRange() {
        ResponseEntity<AssignmentHistoryDTO> response = controller()
                .getHistory(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).getHistory(LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 1));
    }

    @Test
    void rejectsARangeLongerThanTheCap() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = from.plusDays(WorkAssignmentController.MAX_HISTORY_RANGE_DAYS);

        ResponseEntity<AssignmentHistoryDTO> response = controller().getHistory(from, to);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        verify(service, never()).getHistory(from, to);
    }

    /** A single day is a legitimate range. */
    @Test
    void acceptsASingleDay() {
        LocalDate day = LocalDate.of(2026, 10, 12);
        AssignmentHistoryDTO dto = new AssignmentHistoryDTO(day, day, day, List.of());
        when(service.getHistory(day, day)).thenReturn(dto);

        assertThat(controller().getHistory(day, day).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
