package life.work.IntFit.backend.dto.daily_assignments;

import lombok.*;

/**
 * Represents overtime hours worked by a specific team member on a specific date.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OvertimeDTO {

    /** The ID of the team member. */
    private Long teamMemberId;

    /** The overtime hours worked in addition to the default 8h day. */
    private int overtimeHours;
}
