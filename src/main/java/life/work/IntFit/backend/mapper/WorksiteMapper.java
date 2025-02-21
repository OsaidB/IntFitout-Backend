package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.WorksiteDTO;
import life.work.IntFit.backend.model.entity.Worksite;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper(componentModel = "spring")
public interface WorksiteMapper {
    WorksiteMapper INSTANCE = Mappers.getMapper(WorksiteMapper.class);

    WorksiteDTO toDTO(Worksite worksite);

    Worksite toEntity(WorksiteDTO worksiteDTO);

    List<WorksiteDTO> toDTOList(List<Worksite> worksites);

    List<Worksite> toEntityList(List<WorksiteDTO> worksiteDTOs);
}
