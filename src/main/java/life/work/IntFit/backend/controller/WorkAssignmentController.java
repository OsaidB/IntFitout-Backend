package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.daily_assignments.DailyWorkAssignmentDTO;
import life.work.IntFit.backend.dto.daily_assignments.WorkAssignmentDTO;
import life.work.IntFit.backend.service.WorkAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/assignments")
@CrossOrigin("*")
@RequiredArgsConstructor
public class WorkAssignmentController {

    private final WorkAssignmentService workAssignmentService;

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
     * Replaces assignments (and overtime) for the given date.
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
