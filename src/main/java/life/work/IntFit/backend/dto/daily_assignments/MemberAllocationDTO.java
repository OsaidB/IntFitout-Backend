package life.work.IntFit.backend.dto.daily_assignments;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Allocation intent for one member's day. A category left out (or null) is not
 * touched: its stored values are preserved and re-validated against the new day.
 */
@Getter
@Setter
@NoArgsConstructor
public class MemberAllocationDTO {
    private Long teamMemberId;
    private CategoryAllocationDTO regular;
    private CategoryAllocationDTO overtime;

    /** JSON keys repeated inside this entry (e.g. "regular", "overtime.hours[0].hours"); set while parsing. */
    @JsonIgnore
    private List<String> duplicateFields = List.of();

    public MemberAllocationDTO(Long teamMemberId, CategoryAllocationDTO regular, CategoryAllocationDTO overtime) {
        this.teamMemberId = teamMemberId;
        this.regular = regular;
        this.overtime = overtime;
    }
}
