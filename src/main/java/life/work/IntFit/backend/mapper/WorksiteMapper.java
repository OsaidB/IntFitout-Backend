package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.WorksiteDTO;
import life.work.IntFit.backend.model.entity.Worksite;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring", uses = { WorksiteContactMapper.class })
public interface WorksiteMapper {
//    WorksiteMapper INSTANCE = Mappers.getMapper(WorksiteMapper.class);

    @Mapping(target = "contacts", source = "contacts")
    WorksiteDTO toDTO(Worksite worksite);

    @Mapping(target = "contacts", source = "contacts")
    Worksite toEntity(WorksiteDTO worksiteDTO);

    List<WorksiteDTO> toDTOList(List<Worksite> worksites);

    List<Worksite> toEntityList(List<WorksiteDTO> worksiteDTOs);
}
