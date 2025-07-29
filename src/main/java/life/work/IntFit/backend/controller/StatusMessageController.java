package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.model.entity.StatusMessage;
import life.work.IntFit.backend.repository.StatusMessageRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/status-messages")
@CrossOrigin("*") // Allow CORS if you're calling from frontend
public class StatusMessageController {

    private final StatusMessageRepository statusMessageRepository;

    public StatusMessageController(StatusMessageRepository statusMessageRepository) {
        this.statusMessageRepository = statusMessageRepository;
    }

    // Get all status messages (sorted by latest first)
    @GetMapping
    public ResponseEntity<List<StatusMessage>> getAllMessages() {
        List<StatusMessage> messages = statusMessageRepository.findAll()
                .stream()
                .sorted((a, b) -> b.getReceivedAt().compareTo(a.getReceivedAt()))
                .toList();
        return ResponseEntity.ok(messages);
    }

//    // Add a new status message
//    @PostMapping
//    public ResponseEntity<StatusMessage> addStatusMessage(@RequestBody StatusMessage message) {
//        StatusMessage saved = statusMessageRepository.save(message);
//        return ResponseEntity.ok(saved);
//    }

    // Optional: Delete a status message
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStatusMessage(@PathVariable Long id) {
        if (!statusMessageRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        statusMessageRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
