package life.work.IntFit.backend.exception.error;

import life.work.IntFit.backend.dto.daily_assignments.AllocationErrorDTO;

import java.util.List;

/** Thrown before any write when a daily save would leave hour allocations invalid. */
public class AllocationValidationException extends RuntimeException {

    private final transient List<AllocationErrorDTO> errors;

    public AllocationValidationException(List<AllocationErrorDTO> errors) {
        super(summary(errors));
        this.errors = List.copyOf(errors);
    }

    public List<AllocationErrorDTO> getErrors() {
        return errors;
    }

    private static String summary(List<AllocationErrorDTO> errors) {
        long members = errors.stream()
                .map(AllocationErrorDTO::teamMemberId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .count();
        if (members == 0) {
            return "The day was not saved: the hour allocation request is invalid.";
        }
        return "The day was not saved: hour allocations for " + members
                + (members == 1 ? " worker need" : " workers need")
                + " attention. Open the planner for this date to update or reset them.";
    }
}
