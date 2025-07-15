package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.MasterWorksiteDTO;
import life.work.IntFit.backend.service.MasterWorksiteService;
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
    public List<MasterWorksiteDTO> getAll() {
        return service.getAll();
    }

    @PostMapping
    public MasterWorksiteDTO create(@RequestBody MasterWorksiteDTO dto) {
        return service.add(dto.getApprovedName());
    }

    @PostMapping("/{id}/assign")
    public void assignWorksitesToMaster(@PathVariable Long id, @RequestBody List<Long> worksiteIds) {
        service.assignWorksites(id, worksiteIds);
    }


}

