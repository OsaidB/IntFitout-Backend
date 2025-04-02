package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.ContactDTO;
import life.work.IntFit.backend.model.entity.Contact;
import org.mapstruct.Mapper;

import java.util.List;

@Mapper(componentModel = "spring")
public interface ContactMapper {
    ContactDTO toDTO(Contact contact);
    Contact toEntity(ContactDTO contactDTO);

    List<ContactDTO> toDTOs(List<Contact> contacts);
    List<Contact> toEntities(List<ContactDTO> contactDTOs);
}
