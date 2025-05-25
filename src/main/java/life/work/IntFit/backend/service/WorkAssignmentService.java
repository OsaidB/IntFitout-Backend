// --- SERVICE ---
package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.DailyWorkAssignmentDTO;
import life.work.IntFit.backend.dto.WorkAssignmentDTO;

import java.time.LocalDate;
import java.util.List;

public interface WorkAssignmentService {
    List<WorkAssignmentDTO> getAssignmentsByDate(LocalDate date);
    void saveAssignmentsForDate(DailyWorkAssignmentDTO dto);
}
