package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.AreaDTO;
import life.work.IntFit.backend.service.AreaService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/areas")
@CrossOrigin("*")
@RequiredArgsConstructor
public class AreaController {

    private final AreaService areaService;

    @GetMapping
    public ResponseEntity<?> getByCity(@RequestParam String city) {
        if (city == null || city.isBlank()) {
            return ResponseEntity.badRequest().body("city query parameter is required.");
        }
        try {
            List<AreaDTO> areas = areaService.getByCity(city);
            return ResponseEntity.ok(areas);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        String city = body == null ? null : body.get("city");
        String name = body == null ? null : body.get("name");

        if (city == null || city.isBlank()) {
            return ResponseEntity.badRequest().body("city is required.");
        }
        if (name == null || name.isBlank()) {
            return ResponseEntity.badRequest().body("name is required.");
        }
        try {
            AreaDTO saved = areaService.add(city, name);
            return ResponseEntity.ok(saved);
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.badRequest().body(ex.getMessage());
        }
    }
}