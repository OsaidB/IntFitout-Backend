// File: src/main/java/life/work/IntFit/backend/controller/WorksiteCostController.java
package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.expenses.MasterWorksiteCostDetailsDTO;
import life.work.IntFit.backend.dto.expenses.WorksiteCostTotalsDTO;
import life.work.IntFit.backend.service.expenses.WorksiteCostDetailsService;
import life.work.IntFit.backend.service.expenses.WorksiteCostService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;

@CrossOrigin("*")
@RestController
@RequestMapping("/api/master-worksites")
@RequiredArgsConstructor
public class WorksiteCostController {

    private final WorksiteCostService service;

    private final WorksiteCostDetailsService detailsService; // NEW

    @GetMapping("/{id}/cost-details")
    public MasterWorksiteCostDetailsDTO getCostDetails(
            @PathVariable("id") Long masterWorksiteId,
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam("end")   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end
    ) {
        return detailsService.getCostDetails(masterWorksiteId, start, end);
    }

    @GetMapping("/{id}/costs")
    public WorksiteCostTotalsDTO getTotals(
            @PathVariable("id") Long masterWorksiteId,
            @RequestParam("start") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam("end")   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end
    ) {
        return service.computeTotals(masterWorksiteId, start, end);
    }
}
