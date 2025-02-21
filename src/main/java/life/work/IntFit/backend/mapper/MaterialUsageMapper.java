package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.MaterialUsageDTO;
import life.work.IntFit.backend.model.entity.MaterialUsage;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MaterialUsageMapper {
    MaterialUsageMapper INSTANCE = Mappers.getMapper(MaterialUsageMapper.class);

    @Mapping(source = "worksite.id", target = "worksiteId")
    @Mapping(source = "worksite.name", target = "worksiteName")
    MaterialUsageDTO toDTO(MaterialUsage materialUsage);

    MaterialUsage toEntity(MaterialUsageDTO materialUsageDTO);

    List<MaterialUsageDTO> toDTOList(List<MaterialUsage> materialUsages);

    List<MaterialUsage> toEntityList(List<MaterialUsageDTO> materialUsageDTOs);
}
