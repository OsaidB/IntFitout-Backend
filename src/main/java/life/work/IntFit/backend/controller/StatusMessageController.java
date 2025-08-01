package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.model.entity.StatusMessage;
import life.work.IntFit.backend.repository.StatusMessageRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/status-messages")
@CrossOrigin("*")
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

    /*
    @PostMapping
    public ResponseEntity<?> addStatusMessage(@RequestBody StatusMessage message) {
        if (message == null || message.getContent() == null || message.getContent().isBlank()) {
            return ResponseEntity.badRequest().body("Message content is required.");
        }

        StatusMessage saved = statusMessageRepository.save(message);
        return ResponseEntity.ok(saved);
    }
    */

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteStatusMessage(@PathVariable Long id) {
        if (!statusMessageRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }
        statusMessageRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }
}
