package life.work.IntFit.backend.dto.daily_assignments;

import lombok.*;

import java.math.BigDecimal;

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

    /**
     * The overtime hours worked in addition to the default 8h day. Parsed exactly
     * so a CUSTOM overtime allocation can be checked against the submitted text;
     * persisted as a double, as before.
     */
    private BigDecimal overtimeHours;
}
