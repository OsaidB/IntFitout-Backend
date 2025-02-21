package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.WorksiteDTO;
import life.work.IntFit.backend.service.WorksiteService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/worksites")
@CrossOrigin("*")
public class WorksiteController {
    private final WorksiteService worksiteService;

    public WorksiteController(WorksiteService worksiteService) {
        this.worksiteService = worksiteService;
    }

    @GetMapping
    public List<WorksiteDTO> getAllWorksites() {
        return worksiteService.getAllWorksites();
    }

    @GetMapping("/{id}")
    public Optional<WorksiteDTO> getWorksiteById(@PathVariable Long id) {
        return worksiteService.getWorksiteById(id);
    }

    @PostMapping
    public WorksiteDTO addWorksite(@RequestBody WorksiteDTO worksiteDTO) {
        return worksiteService.addWorksite(worksiteDTO);
    }

    @PutMapping("/{id}")
    public WorksiteDTO updateWorksite(@PathVariable Long id, @RequestBody WorksiteDTO updatedDTO) {
        return worksiteService.updateWorksite(id, updatedDTO);
    }

    @DeleteMapping("/{id}")
    public void deleteWorksite(@PathVariable Long id) {
        worksiteService.deleteWorksite(id);
    }
}
