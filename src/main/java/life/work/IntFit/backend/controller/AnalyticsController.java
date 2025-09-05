package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.billing.MonthlyCostsResponseDTO; // ✅ correct import
import life.work.IntFit.backend.service.MonthlyCostService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/analytics")
@CrossOrigin("*")
@RequiredArgsConstructor
public class AnalyticsController {

    private final MonthlyCostService monthlyCostService;


    /**
     * Example:
     * GET /api/analytics/monthly-costs?month=2025-09&profitPercent=15
     * GET /api/analytics/monthly-costs?month=2025-09&masterWorksiteId=123
     */
    @GetMapping("/monthly-costs")
    public MonthlyCostsResponseDTO getMonthlyCosts(
            @RequestParam String month,
            @RequestParam(required = false) Long masterWorksiteId,
            @RequestParam(required = false, defaultValue = "15") Double profitPercent
    ) {
        return monthlyCostService.calculateMonthlyCosts(month, masterWorksiteId, profitPercent);
    }
}


