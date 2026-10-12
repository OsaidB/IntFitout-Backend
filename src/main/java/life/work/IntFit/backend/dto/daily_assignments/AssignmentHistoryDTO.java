package life.work.IntFit.backend.dto.daily_assignments;

import java.time.LocalDate;
import java.util.List;

/**
 * Daily worker counts for a bounded calendar range, for the assignment history
 * screen.
 *
 * The response is deliberately sparse: {@link #days()} holds only dates that
 * have at least one regular assignment, ordered by date. Callers generate the
 * missing calendar days themselves, which keeps the working-week rule
 * (Saturday-Thursday) and the rest-day/empty-day distinction in one place in
 * the client rather than duplicated here.
 *
 * @param from     first date of the requested range, inclusive
 * @param to       last date of the requested range, inclusive
 * @param earliest the earliest assignment date in the whole database, or null
 *                 when no assignment exists at all. This is the history floor:
 *                 a caller should stop paging backwards once it has covered it.
 * @param days     sparse daily counts, ascending by date
 */
public record AssignmentHistoryDTO(
        LocalDate from,
        LocalDate to,
        LocalDate earliest,
        List<DayCount> days
) {
    /**
     * One date's distinct-worker count.
     *
     * @param date    the calendar date
     * @param workers distinct team members with a regular assignment that date.
     *                A worker assigned to several worksites on one date counts
     *                once. Overtime-only records do not contribute.
     */
    public record DayCount(LocalDate date, int workers) {
    }
}
