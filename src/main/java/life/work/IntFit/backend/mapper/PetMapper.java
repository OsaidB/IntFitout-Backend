package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.PetDTO;
import life.work.IntFit.backend.model.entity.Pet;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;
import org.mapstruct.factory.Mappers;

@Mapper(componentModel = "spring")
public interface PetMapper {

    PetMapper INSTANCE = Mappers.getMapper(PetMapper.class);

    @Mappings({
            @Mapping(source = "owner.id", target = "ownerId"),
    })
    PetDTO toDTO(Pet pet);


    @Mappings({
            @Mapping(source = "ownerId", target = "owner.id"),
    })
    Pet toEntity(PetDTO petDTO);
}
