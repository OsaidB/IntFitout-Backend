package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.CurrentTotalOwedDTO;

public interface FinancialSummaryService {

    /**
     * Computes the canonical "Current Total Owed" snapshot:
     * debt (newest status message), checks not due yet, and their total.
     * Read-only; performs no writes.
     */
    CurrentTotalOwedDTO getCurrentTotalOwed();
}
