package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.WorksiteDTO;
import life.work.IntFit.backend.service.WorksiteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/worksites")
@CrossOrigin("*")
public class WorksiteController {
    private final WorksiteService worksiteService;

    public WorksiteController(WorksiteService worksiteService) {
        this.worksiteService = worksiteService;
    }

    @GetMapping
    public ResponseEntity<List<WorksiteDTO>> getAllWorksites() {
        return ResponseEntity.ok(worksiteService.getAllWorksites());
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorksiteDTO> getWorksiteById(@PathVariable Long id) {
        return worksiteService.getWorksiteById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<WorksiteDTO> addWorksite(@RequestBody WorksiteDTO worksiteDTO) {
        if (worksiteDTO == null) return ResponseEntity.badRequest().build();
        WorksiteDTO created = worksiteService.addWorksite(worksiteDTO);
        return ResponseEntity.ok(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<WorksiteDTO> updateWorksite(@PathVariable Long id, @RequestBody WorksiteDTO updatedDTO) {
        WorksiteDTO updated = worksiteService.updateWorksite(id, updatedDTO);
        return ResponseEntity.ok(updated);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteWorksite(@PathVariable Long id) {
        worksiteService.deleteWorksite(id);
        return ResponseEntity.noContent().build();
    }
}
