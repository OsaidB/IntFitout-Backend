package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.MaterialDTO;
import life.work.IntFit.backend.dto.MaterialWithUsageDTO;
import life.work.IntFit.backend.service.MaterialService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/materials")
@RequiredArgsConstructor
@CrossOrigin("*")
public class MaterialController {

    private final MaterialService materialService;

    @GetMapping
    public List<MaterialDTO> getAllMaterials() {
        return materialService.getAllMaterials();
    }

    @GetMapping("/{id}")
    public ResponseEntity<MaterialDTO> getMaterialById(@PathVariable Long id) {
        MaterialDTO material = materialService.getMaterialById(id);
        return material != null ? ResponseEntity.ok(material) : ResponseEntity.notFound().build();
    }

    @PostMapping
    public MaterialDTO addMaterial(@RequestBody MaterialDTO materialDTO) {
        return materialService.addMaterial(materialDTO);
    }

    @PutMapping("/{id}")
    public ResponseEntity<MaterialDTO> updateMaterial(@PathVariable Long id, @RequestBody MaterialDTO materialDTO) {
        MaterialDTO updatedMaterial = materialService.updateMaterial(id, materialDTO);
        return updatedMaterial != null ? ResponseEntity.ok(updatedMaterial) : ResponseEntity.notFound().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteMaterial(@PathVariable Long id) {
        materialService.deleteMaterial(id);
        return ResponseEntity.noContent().build();
    }

    // 🔍 Smart material lookup or creation by name
    @GetMapping("/by-name")
    public ResponseEntity<MaterialDTO> getMaterialByName(@RequestParam String name) {
        MaterialDTO material = materialService.findByName(name);
        return material != null ? ResponseEntity.ok(material) : ResponseEntity.notFound().build();
    }

    @GetMapping("/with-usage")
    public ResponseEntity<List<MaterialWithUsageDTO>> getMaterialsWithUsage() {
        return ResponseEntity.ok(materialService.getMaterialsWithUsage());
    }

    @GetMapping("/{id}/sample-invoice")
    public ResponseEntity<String> getSampleInvoice(@PathVariable Long id) {
        String url = materialService.getSampleInvoiceUrl(id);
        return url != null ? ResponseEntity.ok(url) : ResponseEntity.noContent().build();
    }


}
