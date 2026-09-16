package life.work.IntFit.backend.dto.daily_assignments;

import java.time.LocalDate;
import java.util.List;

/**
 * Everything POST /api/assignments replaces for a date. A whole-day editor
 * should read this, change what the user changed, and send the rest back
 * unchanged; otherwise overtime of workers without assignments is deleted.
 *
 * @param assignments same rows as GET /api/assignments/by-date
 * @param overtime    every stored overtime total for the date, ordered by member id
 */
public record DailyAssignmentsViewDTO(
        LocalDate date,
        List<WorkAssignmentDTO> assignments,
        List<DailyOvertimeDTO> overtime
) {
}
