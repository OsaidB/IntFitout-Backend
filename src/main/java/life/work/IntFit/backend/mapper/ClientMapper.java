package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.ClientDTO;
import life.work.IntFit.backend.model.entity.Client;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper(componentModel = "spring", uses = {PetMapper.class})
public interface ClientMapper {

    ClientMapper INSTANCE = Mappers.getMapper(ClientMapper.class);

    @Mapping(source = "pets", target = "pets")
    ClientDTO toDTO(Client client);

    @Mapping(source = "pets", target = "pets")
    Client toEntity(ClientDTO clientDTO);

    List<ClientDTO> toDTOList(List<Client> clients);

    List<Client> toEntityList(List<ClientDTO> clientDTOs);
}
