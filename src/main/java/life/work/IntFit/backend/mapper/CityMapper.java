package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.CityDTO;
import life.work.IntFit.backend.model.entity.City;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface CityMapper {
    CityDTO toDTO(City city);
    City toEntity(CityDTO dto);
    List<CityDTO> toDTOList(List<City> cities);
}