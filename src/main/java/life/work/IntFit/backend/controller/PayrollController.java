package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.weekly_payroll.*;
import life.work.IntFit.backend.service.PayrollService;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;

@RestController
@RequestMapping("/api/payroll-weeks")
@CrossOrigin("*")
public class PayrollController {

    private final PayrollService service;
    public PayrollController(PayrollService service) { this.service = service; }

    @GetMapping("/generate")
    public PayrollWeekDTO generate(@RequestParam String anchor) {
        return service.generateOrLoad(LocalDate.parse(anchor));
    }

    @PatchMapping("/{weekId}/lines/{lineId}/adjustments")
    public PayrollWeekDTO addAdjustment(
            @PathVariable Long weekId, @PathVariable Long lineId,
            @RequestParam double amount, @RequestParam(required = false) String note) {
        return service.addAdjustment(weekId, lineId, amount, note);
    }

    @PatchMapping("/{weekId}/lines/{lineId}/ot-override")
    public PayrollWeekDTO setOtOverride(
            @PathVariable Long weekId, @PathVariable Long lineId,
            @RequestParam(required = false) Double hours, @RequestParam(required = false) String note) {
        return service.setOtOverride(weekId, lineId, hours, note);
    }

    @PatchMapping("/{weekId}/finalize")
    public PayrollWeekDTO finalizeWeek(@PathVariable Long weekId) {
        return service.finalizeWeek(weekId);
    }
}
