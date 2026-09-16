package life.work.IntFit.backend.dto.daily_assignments;

/**
 * One stored overtime total for the day, including workers with no assignment.
 *
 * @param overtimeHours        the stored value, as in {@link WorkAssignmentDTO#getOvertimeHours()}
 * @param overtimeHoursDecimal exact decimal string of the same value; send this back to preserve it
 * @param hasAssignments       false for raw overtime not attached to any worksite that day
 */
public record DailyOvertimeDTO(
        Long teamMemberId,
        String teamMemberName,
        Double overtimeHours,
        String overtimeHoursDecimal,
        boolean hasAssignments
) {
}
