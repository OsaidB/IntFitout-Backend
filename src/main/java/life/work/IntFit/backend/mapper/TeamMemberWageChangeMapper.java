package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.TeamMemberWageChangeDTO;
import life.work.IntFit.backend.model.entity.TeamMemberWageChange;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface TeamMemberWageChangeMapper {
    @Mapping(source = "teamMember.id", target = "teamMemberId")
    TeamMemberWageChangeDTO toDTO(TeamMemberWageChange entity);
}
