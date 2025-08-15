// package: life.work.IntFit.backend.mapper
package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.daily_assignments.WorkAssignmentDTO;
import life.work.IntFit.backend.model.entity.WorkAssignment;
import org.springframework.stereotype.Component;

@Component
public class WorkAssignmentMapper {

    /** Legacy/basic mapping (no overtime/allocated fields). */
    public WorkAssignmentDTO toDTO(WorkAssignment assignment) {
        WorkAssignmentDTO dto = new WorkAssignmentDTO();
        dto.setId(assignment.getId());
        dto.setTeamMemberId(assignment.getTeamMember().getId());
        dto.setMasterWorksiteId(assignment.getMasterWorksite().getId());
        dto.setTeamMemberName(assignment.getTeamMember().getName());
        dto.setMasterWorksiteName(assignment.getMasterWorksite().getApprovedName());
        dto.setDate(assignment.getDate());
        dto.setTeamMemberDailyWage(assignment.getTeamMember().getDailyWage());
        // overtimeHours / allocatedHours intentionally left null in this variant
        return dto;
    }

    /**
     * Enriched mapping that includes:
     * - overtimeHours: per-member overtime for that date (may be null)
     * - allocatedHours: (8 + overtimeHours) / siteCountForMember (null if siteCountForMember == null/0)
     */
    public WorkAssignmentDTO toDTO(WorkAssignment assignment, Integer overtimeHours, Integer siteCountForMember) {
        WorkAssignmentDTO dto = toDTO(assignment);
        dto.setOvertimeHours(overtimeHours);


        if (siteCountForMember != null && siteCountForMember > 0) {
            int ot = (overtimeHours != null && overtimeHours >= 0) ? overtimeHours : 0;
            double allocated = (8.0 + ot) / siteCountForMember;
            dto.setAllocatedHours(round2(allocated));
        } else {
            dto.setAllocatedHours(null);
        }

        return dto;
    }

    private Double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}
