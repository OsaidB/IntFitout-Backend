package life.work.IntFit.backend.dto.daily_assignments;

import lombok.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Carries all assignments for a specific date.
 * - assignments: flat list of (teamMemberId, masterWorksiteId)
 * - overtime: per-member overtime hours to be added to the default 8h day
 */
@Getter
@Setter
@NoArgsConstructor
public class DailyWorkAssignmentDTO {
    private LocalDate date;

    /** Simple per-site assignments (no start/end times). */
    private List<SimpleAssignmentDTO> assignments;

    /**
     * Per-member overtime in hours (>= 0). If a member is assigned to N sites,
     * total hours for that member = 8 + overtimeHours, split evenly across the N sites.
     */
    private List<OvertimeDTO> overtime;
}
