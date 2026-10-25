package life.work.IntFit.backend.controller;

import jakarta.persistence.EntityNotFoundException;
import life.work.IntFit.backend.dto.MasterWorksiteDTO;
import life.work.IntFit.backend.service.MasterWorksiteService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/master-worksites")
@CrossOrigin("*")
public class MasterWorksiteController {

    private final MasterWorksiteService service;

    public MasterWorksiteController(MasterWorksiteService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<List<MasterWorksiteDTO>> getAll() {
        return ResponseEntity.ok(service.getAll());
    }

    /** ➕ NEW: get single by ID */
    @GetMapping("/{id}")
    public ResponseEntity<?> getOne(@PathVariable Long id) {
        try {
            MasterWorksiteDTO dto = service.getById(id);
            return ResponseEntity.ok(dto);
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Master worksite not found: #" + id);
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody MasterWorksiteDTO dto) {
        if (dto == null || dto.getApprovedName() == null || dto.getApprovedName().isBlank()) {
            System.err.println("❌ Invalid approvedName in request");
            return ResponseEntity.badRequest().body("Approved name is required.");
        }

        try { return ResponseEntity.ok(service.add(dto)); }
        catch (IllegalArgumentException ex) { return ResponseEntity.badRequest().body(ex.getMessage()); }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody MasterWorksiteDTO dto) {
        if (dto == null || dto.getApprovedName() == null || dto.getApprovedName().isBlank()) {
            return ResponseEntity.badRequest().body("Approved name is required.");
        }
        try {
            MasterWorksiteDTO updated = service.update(id, dto);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
    }

    @PostMapping("/{id}/assign")
    public ResponseEntity<?> assignWorksitesToMaster(@PathVariable Long id, @RequestBody List<Long> worksiteIds) {
        if (worksiteIds == null || worksiteIds.isEmpty()) {
            System.err.println("❌ Worksite ID list is empty or null");
            return ResponseEntity.badRequest().body("Worksite ID list must not be empty.");
        }

        service.assignWorksites(id, worksiteIds);
        return ResponseEntity.ok().build();
    }

    // 👇 NEW: update notes
    @PutMapping("/{id}/nickname")
    public ResponseEntity<?> nickname(@PathVariable Long id,@RequestBody Map<String,String> body) {
        if(body==null || !body.keySet().equals(java.util.Set.of("nickname")) || body.get("nickname")==null)
            return ResponseEntity.badRequest().body("Provide nickname as a string; use an empty string to clear it.");
        try { return ResponseEntity.ok(service.updateNickname(id,body.get("nickname"))); }
        catch(IllegalArgumentException e) { return ResponseEntity.badRequest().body(e.getMessage()); }
    }

    @PutMapping("/{id}/notes")
    public ResponseEntity<MasterWorksiteDTO> updateNotes(
            @PathVariable Long id,
            @RequestBody Map<String, String> body
    ) {
        String notes = body.getOrDefault("notes", "");
        MasterWorksiteDTO updated = service.updateNotes(id, notes);
        return ResponseEntity.ok(updated);
    }

    /** ➕ NEW: delete by ID (matches your frontend delete call) */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (EntityNotFoundException ex) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body("Master worksite not found: #" + id);
        }
    }

}

