package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.MasterWorksiteDTO;
import life.work.IntFit.backend.service.MasterWorksiteService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @PostMapping
    public ResponseEntity<?> create(@RequestBody MasterWorksiteDTO dto) {
        if (dto == null || dto.getApprovedName() == null || dto.getApprovedName().isBlank()) {
            System.err.println("❌ Invalid approvedName in request");
            return ResponseEntity.badRequest().body("Approved name is required.");
        }

        MasterWorksiteDTO saved = service.add(dto.getApprovedName());
        return ResponseEntity.ok(saved);
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


}

