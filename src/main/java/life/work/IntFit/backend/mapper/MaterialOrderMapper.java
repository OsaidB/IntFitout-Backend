package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.MaterialOrderDTO;
import life.work.IntFit.backend.model.entity.MaterialOrder;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

@Mapper(componentModel = "spring", uses = { MaterialOrderItemMapper.class })
public interface MaterialOrderMapper {
    MaterialOrderMapper INSTANCE = Mappers.getMapper(MaterialOrderMapper.class);

    @Mapping(source = "worksite.id", target = "worksiteId")
    @Mapping(source = "worksite.name", target = "worksiteName")
    @Mapping(source = "items", target = "items")  // Use MaterialOrderItemMapper
    MaterialOrderDTO toDTO(MaterialOrder order);

    @Mapping(source = "worksiteId", target = "worksite.id")
    @Mapping(source = "items", target = "items")  // Use MaterialOrderItemMapper
    MaterialOrder toEntity(MaterialOrderDTO orderDTO);

    List<MaterialOrderDTO> toDTOList(List<MaterialOrder> orders);

    List<MaterialOrder> toEntityList(List<MaterialOrderDTO> orderDTOs);
}
