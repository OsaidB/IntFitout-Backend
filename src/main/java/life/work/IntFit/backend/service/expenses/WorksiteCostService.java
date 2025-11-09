package life.work.IntFit.backend.service.expenses;

// File: src/main/java/life/work/IntFit/backend/service/WorksiteCostService.java

import life.work.IntFit.backend.dto.expenses.WorksiteCostTotalsDTO;

import java.time.LocalDate;

public interface WorksiteCostService {
    WorksiteCostTotalsDTO computeTotals(Long masterWorksiteId, LocalDate start, LocalDate end);
}
