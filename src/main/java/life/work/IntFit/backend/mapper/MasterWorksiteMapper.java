package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.MasterWorksiteDTO;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface MasterWorksiteMapper {
    MasterWorksiteDTO toDTO(MasterWorksite masterWorksite);
    MasterWorksite toEntity(MasterWorksiteDTO masterWorksiteDTO);
    List<MasterWorksiteDTO> toDTOList(List<MasterWorksite> entities);
}
