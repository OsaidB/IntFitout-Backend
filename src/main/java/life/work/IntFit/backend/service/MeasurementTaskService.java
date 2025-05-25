package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.MeasurementTaskDTO;
import life.work.IntFit.backend.mapper.MeasurementTaskMapper;
import life.work.IntFit.backend.model.entity.MeasurementTask;
import life.work.IntFit.backend.repository.MeasurementTaskRepository;
import life.work.IntFit.backend.repository.RoomRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class MeasurementTaskService {
    private final MeasurementTaskRepository taskRepository;
    private final MeasurementTaskMapper taskMapper;

    private final RoomRepository roomRepository;

    public MeasurementTaskDTO updateTask(Long id, MeasurementTaskDTO updatedDTO) {
        MeasurementTask existing = taskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Task not found"));

        existing.setTaskType(updatedDTO.getTaskType());
        existing.setLength(updatedDTO.getLength());
        existing.setWidth(updatedDTO.getWidth());
        existing.setHeight(updatedDTO.getHeight());
        existing.setUnit(updatedDTO.getUnit());
        existing.setUnitCost(updatedDTO.getUnitCost());
        existing.setCalculationType(updatedDTO.getCalculationType());

        return taskMapper.toDTO(taskRepository.save(existing));
    }


    public MeasurementTaskService(MeasurementTaskRepository taskRepository,
                                  MeasurementTaskMapper taskMapper,
                                  RoomRepository roomRepository) {
        this.taskRepository = taskRepository;
        this.taskMapper = taskMapper;
        this.roomRepository = roomRepository;
    }


    public List<MeasurementTaskDTO> getTasksByRoomId(Long roomId) {
        List<MeasurementTask> tasks = taskRepository.findByRoomId(roomId);
        return taskMapper.toDTOs(tasks);
    }

    public MeasurementTaskDTO addTask(MeasurementTaskDTO taskDTO) {
        MeasurementTask task = taskMapper.toEntity(taskDTO);

        // 🔥 Manually fetch and set the Room entity
        if (taskDTO.getRoomId() != null) {
            task.setRoom(
                    roomRepository.findById(taskDTO.getRoomId())
                            .orElseThrow(() -> new IllegalArgumentException("Invalid room ID: " + taskDTO.getRoomId()))
            );
        }

        MeasurementTask savedTask = taskRepository.save(task);
        return taskMapper.toDTO(savedTask);
    }

    public Optional<MeasurementTaskDTO> getTaskById(Long id) {
        return taskRepository.findById(id).map(taskMapper::toDTO);
    }

    public void deleteTask(Long id) {
        taskRepository.deleteById(id);
    }
}
