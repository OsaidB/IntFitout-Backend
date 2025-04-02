package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.WorksiteContactDTO;
import life.work.IntFit.backend.model.entity.WorksiteContact;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", uses = {ContactMapper.class})
public interface WorksiteContactMapper {

    @Mapping(source = "worksite.id", target = "worksiteId")
    @Mapping(source = "contact.id", target = "contactId")
    @Mapping(source = "contact", target = "contact") // nested ContactDTO
    WorksiteContactDTO toDTO(WorksiteContact entity);

    @Mapping(source = "worksiteId", target = "worksite.id")
    @Mapping(source = "contactId", target = "contact.id")
    WorksiteContact toEntity(WorksiteContactDTO dto);

    List<WorksiteContactDTO> toDTOs(List<WorksiteContact> list);
    List<WorksiteContact> toEntities(List<WorksiteContactDTO> list);
}
