// File: src/main/java/life/work/IntFit/backend/controller/WorksiteCostController.java
package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.expenses.WorksiteCostTotalsDTO;
import life.work.IntFit.backend.service.expenses.WorksiteCostService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@RestController
@RequestMapping("/api/master-worksites")
@RequiredArgsConstructor
public class WorksiteCostController {

    private final WorksiteCostService service;

    @GetMapping("/{id}/costs")
    public WorksiteCostTotalsDTO getTotals(
            @PathVariable("id") Long masterWorksiteId,
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam("end")   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end
    ) {
        return service.computeTotals(masterWorksiteId, start, end);
    }
}
