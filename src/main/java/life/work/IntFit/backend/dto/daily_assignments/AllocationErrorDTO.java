package life.work.IntFit.backend.dto.daily_assignments;

/**
 * One reason a daily save was rejected.
 *
 * @param category "REGULAR", "OVERTIME", or null for request-level problems
 * @param code     stable machine-readable reason
 */
public record AllocationErrorDTO(
        Long teamMemberId,
        String teamMemberName,
        String category,
        String code,
        String message
) {
}
