package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.MaterialOrderItemDTO;
import life.work.IntFit.backend.model.entity.MaterialOrderItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MaterialOrderItemMapper {
    MaterialOrderItemMapper INSTANCE = Mappers.getMapper(MaterialOrderItemMapper.class);

    @Mapping(source = "material.id", target = "materialId")
    @Mapping(source = "material.name", target = "materialName")
    MaterialOrderItemDTO toDTO(MaterialOrderItem materialOrderItem);

    @Mapping(source = "materialId", target = "material.id")
    MaterialOrderItem toEntity(MaterialOrderItemDTO materialOrderItemDTO);

    List<MaterialOrderItemDTO> toDTOList(List<MaterialOrderItem> items);

    List<MaterialOrderItem> toEntityList(List<MaterialOrderItemDTO> itemDTOs);
}
