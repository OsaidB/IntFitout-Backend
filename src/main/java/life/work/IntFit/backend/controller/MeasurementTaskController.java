package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.MeasurementTaskDTO;
import life.work.IntFit.backend.service.MeasurementTaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tasks")
@CrossOrigin("*")
public class MeasurementTaskController {

    private final MeasurementTaskService taskService;

    public MeasurementTaskController(MeasurementTaskService taskService) {
        this.taskService = taskService;
    }

    @PutMapping("/{id}")
    public ResponseEntity<MeasurementTaskDTO> updateTask(@PathVariable Long id, @RequestBody MeasurementTaskDTO dto) {
        MeasurementTaskDTO updated = taskService.updateTask(id, dto);
        return ResponseEntity.ok(updated);
    }


    @GetMapping("/by-room/{roomId}")
    public ResponseEntity<List<MeasurementTaskDTO>> getTasksByRoom(@PathVariable Long roomId) {
        List<MeasurementTaskDTO> tasks = taskService.getTasksByRoomId(roomId);
        return ResponseEntity.ok(tasks);
    }

    @PostMapping
    public ResponseEntity<MeasurementTaskDTO> addTask(@RequestBody MeasurementTaskDTO taskDTO) {
        MeasurementTaskDTO created = taskService.addTask(taskDTO);
        return ResponseEntity.ok(created);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MeasurementTaskDTO> getTask(@PathVariable Long id) {
        return taskService.getTaskById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTask(@PathVariable Long id) {
        taskService.deleteTask(id);
        return ResponseEntity.noContent().build();
    }
}
