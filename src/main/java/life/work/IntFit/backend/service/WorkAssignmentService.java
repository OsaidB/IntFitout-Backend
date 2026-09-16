// --- SERVICE ---
package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.daily_assignments.DailyAssignmentsViewDTO;
import life.work.IntFit.backend.dto.daily_assignments.DailyWorkAssignmentDTO;
import life.work.IntFit.backend.dto.daily_assignments.WorkAssignmentDTO;

import java.time.LocalDate;
import java.util.List;

public interface WorkAssignmentService {
    List<WorkAssignmentDTO> getAssignmentsByDate(LocalDate date);
    DailyAssignmentsViewDTO getDay(LocalDate date);
    void saveAssignmentsForDate(DailyWorkAssignmentDTO dto);
}
