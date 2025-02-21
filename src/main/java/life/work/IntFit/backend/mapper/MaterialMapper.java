package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.MaterialDTO;
import life.work.IntFit.backend.model.entity.Material;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MaterialMapper {
    MaterialMapper INSTANCE = Mappers.getMapper(MaterialMapper.class);

    MaterialDTO toDTO(Material material);
    Material toEntity(MaterialDTO materialDTO);

    List<MaterialDTO> toDTOList(List<Material> materials);
    List<Material> toEntityList(List<MaterialDTO> materialDTOs);
}
