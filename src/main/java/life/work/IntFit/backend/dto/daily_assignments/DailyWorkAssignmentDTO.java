package life.work.IntFit.backend.dto.daily_assignments;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Carries all assignments for a specific date.
 * - assignments: flat list of (teamMemberId, masterWorksiteId)
 * - overtime: per-member overtime hours to be added to the default 8h day
 * - allocations: optional per-member regular/overtime allocation intent
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
     * total hours for that member = 8 + overtimeHours, split evenly across the N sites
     * unless the member's overtime allocation is CUSTOM.
     */
    private List<OvertimeDTO> overtime;

    /**
     * Optional. A member (or category) that is not listed keeps its stored
     * allocation, which must still be valid for the submitted day or the whole
     * save is rejected. Send mode "AUTO" to reset a category explicitly.
     */
    @Setter(AccessLevel.NONE)
    @JsonDeserialize(using = MemberAllocationListDeserializer.class)
    private List<MemberAllocationDTO> allocations;

    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @JsonIgnore
    private int allocationsFieldCount;

    public void setAllocations(List<MemberAllocationDTO> allocations) {
        this.allocations = allocations;
        this.allocationsFieldCount++;
    }

    /** True when the request body contained the "allocations" key more than once. */
    @JsonIgnore
    public boolean isAllocationsRepeated() {
        return allocationsFieldCount > 1;
    }
}
