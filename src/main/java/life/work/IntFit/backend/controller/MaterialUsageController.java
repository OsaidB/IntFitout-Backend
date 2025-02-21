package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.MaterialUsageDTO;
import life.work.IntFit.backend.service.MaterialUsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/material-usage")
@RequiredArgsConstructor
@CrossOrigin("*")
public class MaterialUsageController {
    private final MaterialUsageService materialUsageService;

    @GetMapping
    public List<MaterialUsageDTO> getAllMaterialUsages() {
        return materialUsageService.getAllMaterialUsages();
    }

    @PostMapping
    public MaterialUsageDTO addMaterialUsage(@RequestBody MaterialUsageDTO materialUsageDTO) {
        return materialUsageService.addMaterialUsage(materialUsageDTO);
    }
}
