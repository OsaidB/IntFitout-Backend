package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.DailyWorkAssignmentDTO;
import life.work.IntFit.backend.dto.WorkAssignmentDTO;
import life.work.IntFit.backend.service.WorkAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/work-assignments")
@RequiredArgsConstructor
@CrossOrigin("*")
public class WorkAssignmentController {

    private final WorkAssignmentService workAssignmentService;

    @PostMapping
    public ResponseEntity<Void> saveAssignments(@RequestBody DailyWorkAssignmentDTO dto) {
        if (dto == null || dto.getDate() == null || dto.getAssignments() == null) {
            return ResponseEntity.badRequest().build();
        }

        workAssignmentService.saveAssignmentsForDate(dto);
        return ResponseEntity.ok().build();
    }

    @GetMapping
    public ResponseEntity<List<WorkAssignmentDTO>> getAssignmentsByDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(workAssignmentService.getAssignmentsByDate(date));
    }
}
