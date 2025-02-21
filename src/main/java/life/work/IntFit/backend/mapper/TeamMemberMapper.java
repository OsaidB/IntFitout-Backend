package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.TeamMemberDTO;
import life.work.IntFit.backend.model.entity.TeamMember;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper(componentModel = "spring")
public interface TeamMemberMapper {
    TeamMemberMapper INSTANCE = Mappers.getMapper(TeamMemberMapper.class);

    TeamMemberDTO toDTO(TeamMember teamMember);

    TeamMember toEntity(TeamMemberDTO teamMemberDTO);

    List<TeamMemberDTO> toDTOList(List<TeamMember> teamMembers);

    List<TeamMember> toEntityList(List<TeamMemberDTO> teamMemberDTOs);
}
