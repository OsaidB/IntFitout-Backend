package life.work.IntFit.backend.dto.daily_assignments;

import lombok.*;

import java.time.LocalDate;

/**
 * Flat view of a single (member ↔ worksite) assignment for a given date.
 * For the new workflow:
 * - overtimeHours: per-member daily overtime (may be repeated across rows for that member)
 * - allocatedHours: derived hours for THIS assignment = (8 + overtimeHours) / siteCountForMember
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WorkAssignmentDTO {
    private Long id;

    private Long teamMemberId;
    private Long masterWorksiteId;

    private String teamMemberName;
    private String masterWorksiteName;

    private LocalDate date;

    /** Optional: member’s daily wage if you compute it elsewhere. */
    private Double teamMemberDailyWage;

    /** Per-member overtime for that date (>= 0). Nullable if not loaded. */
    private Integer overtimeHours;

    /** Derived hours for this specific assignment (split equally). Nullable if not computed. */
    private Double allocatedHours;
}
