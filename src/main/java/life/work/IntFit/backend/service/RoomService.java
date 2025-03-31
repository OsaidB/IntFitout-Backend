package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.RoomDTO;
import life.work.IntFit.backend.mapper.RoomMapper;
import life.work.IntFit.backend.model.entity.Room;
import life.work.IntFit.backend.repository.RoomRepository;
import life.work.IntFit.backend.repository.WorksiteRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class RoomService {
    private final RoomRepository roomRepository;
    private final RoomMapper roomMapper;
    private final WorksiteRepository worksiteRepository;

    public RoomService(RoomRepository roomRepository, RoomMapper roomMapper, WorksiteRepository worksiteRepository) {
        this.roomRepository = roomRepository;
        this.roomMapper = roomMapper;
        this.worksiteRepository = worksiteRepository;
    }

    public List<RoomDTO> getRoomsByWorksiteId(Long worksiteId) {
        List<Room> rooms = roomRepository.findByWorksiteIdWithTasks(worksiteId);
        return roomMapper.toDTOs(rooms);
    }


    public RoomDTO addRoom(RoomDTO roomDTO) {
        Room room = roomMapper.toEntity(roomDTO);

        if (roomDTO.getWorksiteId() != null) {
            worksiteRepository.findById(roomDTO.getWorksiteId())
                    .ifPresent(room::setWorksite); // 🔥 fix the missing link here
        }

        Room savedRoom = roomRepository.save(room);
        return roomMapper.toDTO(savedRoom);
    }

    public Optional<RoomDTO> getRoomById(Long id) {
        return roomRepository.findByIdWithTasks(id)
                .map(roomMapper::toDTO);
    }


    public void deleteRoom(Long id) {
        roomRepository.deleteById(id);
    }
}
