package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.daily_assignments.AssignmentHistoryDTO;
import life.work.IntFit.backend.dto.daily_assignments.DailyAssignmentsViewDTO;
import life.work.IntFit.backend.dto.daily_assignments.DailyWorkAssignmentDTO;
import life.work.IntFit.backend.dto.daily_assignments.WorkAssignmentDTO;
import life.work.IntFit.backend.service.WorkAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/assignments")
@CrossOrigin("*")
@RequiredArgsConstructor
public class WorkAssignmentController {

    private final WorkAssignmentService workAssignmentService;

    /**
     * Longest range one history request may cover. The history screen asks for
     * one calendar month at a time; 92 days leaves room for a quarter without
     * letting a caller pull the whole table in one request.
     */
    static final long MAX_HISTORY_RANGE_DAYS = 92;

    /**
     * Returns all (member ↔ worksite) assignments for a date.
     * Response rows include:
     *  - overtimeHours (per member)
     *  - allocatedHours = (8 + overtimeHours) / siteCountForThatMember
     */
    @GetMapping("/by-date")
    public ResponseEntity<List<WorkAssignmentDTO>> getByDate(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        List<WorkAssignmentDTO> result = workAssignmentService.getAssignmentsByDate(date);
        return ResponseEntity.ok(result);
    }

    /**
     * The whole day as POST replaces it: the by-date rows plus every overtime
     * total, including workers with no assignment. Use this for read-modify-write.
     */
    @GetMapping("/day")
    public ResponseEntity<DailyAssignmentsViewDTO> getDay(
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ResponseEntity.ok(workAssignmentService.getDay(date));
    }

    /**
     * Daily distinct-worker counts for an inclusive calendar range, plus the
     * earliest assignment date in the database.
     *
     * Only dates that have at least one regular assignment are returned; the
     * caller generates the missing calendar days, which keeps the working-week
     * rule in the client. A worker on several worksites in one day counts once.
     *
     * Returns 400 when a bound is missing, the range is inverted, or it spans
     * more than {@link #MAX_HISTORY_RANGE_DAYS} days.
     */
    @GetMapping("/history")
    public ResponseEntity<AssignmentHistoryDTO> getHistory(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to
    ) {
        if (from == null || to == null || to.isBefore(from)) {
            return ResponseEntity.badRequest().build();
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_HISTORY_RANGE_DAYS) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(workAssignmentService.getHistory(from, to));
    }

    /**
     * Replaces assignments, overtime and hour allocations for the given date.
     * Returns 422 with per-member errors, and saves nothing, when the resulting
     * allocations would be invalid. Optional "allocations" are documented on
     * {@link DailyWorkAssignmentDTO}.
     * Expects payload:
     * {
     *   "date": "2025-08-15",
     *   "assignments": [
     *     {"teamMemberId": 1, "masterWorksiteId": 10},
     *     {"teamMemberId": 1, "masterWorksiteId": 12},
     *     {"teamMemberId": 2, "masterWorksiteId": 10}
     *   ],
     *   "overtime": [
     *     {"teamMemberId": 1, "overtimeHours": 4},
     *     {"teamMemberId": 2, "overtimeHours": 0}
     *   ]
     * }
     */
    @PostMapping
    public ResponseEntity<Void> saveForDate(@RequestBody DailyWorkAssignmentDTO dto) {
        if (dto == null || dto.getDate() == null) {
            return ResponseEntity.badRequest().build();
        }

        workAssignmentService.saveAssignmentsForDate(dto);
        return ResponseEntity.noContent().build();
    }
}
