// File: src/main/java/life/work/IntFit/backend/controller/MasterWorksiteStartController.java
package life.work.IntFit.backend.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import life.work.IntFit.backend.dto.MasterWorksiteStartSummaryDTO;
import life.work.IntFit.backend.service.MasterWorksiteStartService;

@RestController
@RequestMapping("/api/master-worksites")
@CrossOrigin("*") // per your project convention
@RequiredArgsConstructor
public class MasterWorksiteStartController {

    private final MasterWorksiteStartService service;

    @GetMapping("/{id}/start-summary")
    public ResponseEntity<MasterWorksiteStartSummaryDTO> getStartSummary(@PathVariable("id") Long id) {
        return ResponseEntity.ok(service.getStartSummary(id));
    }
}
