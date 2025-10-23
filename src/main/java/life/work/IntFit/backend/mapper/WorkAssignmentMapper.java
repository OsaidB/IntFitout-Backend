// package: life.work.IntFit.backend.mapper
package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.daily_assignments.WorkAssignmentDTO;
import life.work.IntFit.backend.model.entity.WorkAssignment;
import org.springframework.stereotype.Component;

@Component
public class WorkAssignmentMapper {

    private static final double BASE_DAY_HOURS = 8.0;

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
    public WorkAssignmentDTO toDTO(WorkAssignment assignment, Double overtimeHours, Integer siteCountForMember) {
        WorkAssignmentDTO dto = toDTO(assignment);

        // Keep null if not provided (caller may use null to indicate "not loaded")
        dto.setOvertimeHours(overtimeHours);

        if (siteCountForMember != null && siteCountForMember > 0) {
            double otForSplit = (overtimeHours != null && overtimeHours > 0d) ? overtimeHours : 0d;
            double allocated = (BASE_DAY_HOURS + otForSplit) / siteCountForMember;
            dto.setAllocatedHours(round2(allocated));
        } else {
            dto.setAllocatedHours(null);
        }

        return dto;
    }

    /** Backward-compat overload: accepts Integer OT and delegates to Double version. */
    public WorkAssignmentDTO toDTO(WorkAssignment assignment, Integer overtimeHours, Integer siteCountForMember) {
        Double ot = (overtimeHours == null) ? null : overtimeHours.doubleValue();
        return toDTO(assignment, ot, siteCountForMember);
    }

    private Double round2(double val) {
        return Math.round(val * 100.0) / 100.0;
    }
}
