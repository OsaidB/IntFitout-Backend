// src/main/java/life/work/IntFit/backend/controller/ExtraCostController.java
package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.ExtraCostDTO;
import life.work.IntFit.backend.service.ExtraCostService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/extra-costs")
@CrossOrigin // tune origins as needed
public class ExtraCostController {
    private final ExtraCostService svc;
    public ExtraCostController(ExtraCostService svc) { this.svc = svc; }

    @GetMapping("/by-date")
    public ResponseEntity<List<ExtraCostDTO>> byDate(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) Long masterWorksiteId
    ) {
        return svc.byDate(date, masterWorksiteId);
    }

    @GetMapping("/general")
    public ResponseEntity<List<ExtraCostDTO>> general(@RequestParam Long masterWorksiteId) {
        return svc.general(masterWorksiteId);
    }

    @GetMapping("/by-master-in-range")
    public ResponseEntity<List<ExtraCostDTO>> inRange(
            @RequestParam Long masterWorksiteId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end
    ) {
        return svc.inRange(masterWorksiteId, start, end);
    }

    @PostMapping
    public ResponseEntity<ExtraCostDTO> create(@RequestBody ExtraCostDTO dto) {
        return ResponseEntity.status(HttpStatus.CREATED).body(svc.create(dto));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ExtraCostDTO> get(@PathVariable Long id) {
        var dto = svc.get(id);
        return dto == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(dto);
    }

    @PutMapping("/{id}")
    public ResponseEntity<ExtraCostDTO> update(@PathVariable Long id, @RequestBody ExtraCostDTO dto) {
        return ResponseEntity.ok(svc.update(id, dto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        svc.delete(id);
        return ResponseEntity.noContent().build();
    }
}
