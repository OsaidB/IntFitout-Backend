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
        dto.setWorksiteId(assignment.getWorksite().getId());
        dto.setTeamMemberName(assignment.getTeamMember().getName());
        dto.setWorksiteName(assignment.getWorksite().getName());
        dto.setDate(assignment.getDate());
        return dto;
    }
}