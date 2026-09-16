package life.work.IntFit.backend.dto.daily_assignments;

import life.work.IntFit.backend.model.enums.AllocationMode;
import lombok.*;

import java.time.LocalDate;

/**
 * Flat view of a single (member ↔ worksite) assignment for a given date.
 * - overtimeHours: per-member daily overtime (may be repeated across rows for that member)
 * - allocatedHours: this row's hours rounded to 2 decimals, for display only
 * - resolved*Hours: unrounded hours for this row; use resolvedTotalHours for money
 */
@Getter
@Setter
@NoArgsConstructor
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
    private Double overtimeHours; // was Integer

    /** Exact decimal string of {@link #overtimeHours}; use it for allocation arithmetic. Null when there is no overtime row. */
    private String overtimeHoursDecimal;

    /** Display value: resolvedTotalHours rounded to 2 decimals. Nullable if not computed. */
    private Double allocatedHours;

    /** Number of worksites the member is assigned to that day. */
    private Integer siteCount;

    private AllocationMode regularAllocationMode;
    private AllocationMode overtimeAllocationMode;

    /** Saved custom value as an exact decimal string; null when the category is AUTO. */
    private String regularHoursOverride;
    private String overtimeHoursOverride;

    /** Unrounded resolved hours for this row. */
    private Double resolvedRegularHours;
    private Double resolvedOvertimeHours;
    private Double resolvedTotalHours;
}
