package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.RoomDTO;
import life.work.IntFit.backend.model.entity.Room;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", uses = { MeasurementTaskMapper.class })
public interface RoomMapper {

    RoomDTO toDTO(Room room);

    Room toEntity(RoomDTO roomDTO);

    List<RoomDTO> toDTOs(List<Room> rooms);

    List<Room> toEntities(List<RoomDTO> roomDTOs);
}
