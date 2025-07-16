package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.WorkAssignmentDTO;
import life.work.IntFit.backend.model.entity.WorkAssignment;
import org.springframework.stereotype.Component;

@Component
public class WorkAssignmentMapper {

    public WorkAssignmentDTO toDTO(WorkAssignment assignment) {
        WorkAssignmentDTO dto = new WorkAssignmentDTO();
        dto.setId(assignment.getId());
        dto.setTeamMemberId(assignment.getTeamMember().getId());
        dto.setMasterWorksiteId(assignment.getMasterWorksite().getId());
        dto.setTeamMemberName(assignment.getTeamMember().getName());
        dto.setMasterWorksiteName(assignment.getMasterWorksite().getApprovedName());
        dto.setDate(assignment.getDate());
        return dto;
    }
}