package life.work.IntFit.backend.service.expenses;

import life.work.IntFit.backend.dto.expenses.MasterWorksiteCostDetailsDTO;

import java.time.LocalDate;

public interface WorksiteCostDetailsService {
    MasterWorksiteCostDetailsDTO getCostDetails(Long masterId, LocalDate start, LocalDate end);
}
