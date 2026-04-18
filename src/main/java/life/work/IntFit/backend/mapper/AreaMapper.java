package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.AreaDTO;
import life.work.IntFit.backend.model.entity.Area;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface AreaMapper {

    @Mapping(target = "cityId", source = "city.id")
    @Mapping(target = "cityName", source = "city.name")
    AreaDTO toDTO(Area area);

    List<AreaDTO> toDTOList(List<Area> areas);
}