package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.CurrentTotalOwedDTO;
import life.work.IntFit.backend.service.FinancialSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/financial-summary")
@RequiredArgsConstructor
@CrossOrigin("*")
public class FinancialSummaryController {

    private final FinancialSummaryService financialSummaryService;

    /**
     * GET /api/financial-summary/current-total-owed
     * Read-only canonical snapshot of Debt, Checks-not-due-yet, and their total.
     * Always returns 200; empty data yields zeros.
     */
    @GetMapping("/current-total-owed")
    public CurrentTotalOwedDTO currentTotalOwed() {
        return financialSummaryService.getCurrentTotalOwed();
    }
}
