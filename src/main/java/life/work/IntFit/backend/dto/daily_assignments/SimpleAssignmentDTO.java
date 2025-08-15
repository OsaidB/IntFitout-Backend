package life.work.IntFit.backend.dto.daily_assignments;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Represents a flat assignment of a team member to a master worksite for a given day.
 * No start/end times are included — daily hours are split evenly across
 * all worksites assigned to the member (plus any overtime from OvertimeDTO).
 */
@Getter
@Setter
@NoArgsConstructor
public class SimpleAssignmentDTO {
    private Long teamMemberId;
    private Long masterWorksiteId;
}
