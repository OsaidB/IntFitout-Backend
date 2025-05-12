package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.MaterialDTO;
import life.work.IntFit.backend.model.entity.Material;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MaterialMapper {

    MaterialDTO toDTO(Material material);

    Material toEntity(MaterialDTO dto);

    List<MaterialDTO> toDTOList(List<Material> materials);

    List<Material> toEntityList(List<MaterialDTO> materialDTOs);
}
