package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.model.entity.WorksiteContact;
import life.work.IntFit.backend.service.WorksiteContactService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/worksite-contacts")
@CrossOrigin("*")
public class WorksiteContactController {

    private final WorksiteContactService worksiteContactService;

    public WorksiteContactController(WorksiteContactService worksiteContactService) {
        this.worksiteContactService = worksiteContactService;
    }

    @GetMapping("/worksite/{worksiteId}")
    public List<WorksiteContact> getByWorksite(@PathVariable Long worksiteId) {
        return worksiteContactService.getAllByWorksiteId(worksiteId);
    }

    @PostMapping
    public WorksiteContact addWorksiteContact(@RequestBody Map<String, Object> payload) {
        Long worksiteId = Long.valueOf(payload.get("worksiteId").toString());
        Long contactId = Long.valueOf(payload.get("contactId").toString());
        String note = payload.get("note") != null ? payload.get("note").toString() : null;

        return worksiteContactService.addWorksiteContact(worksiteId, contactId, note);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        worksiteContactService.deleteWorksiteContact(id);
        return ResponseEntity.noContent().build();
    }
}
