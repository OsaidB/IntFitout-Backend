package life.work.IntFit.backend.dto.billing;

import lombok.*;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MonthlyCostsResponseDTO {
    private String month;                 // "2025-09"
    private Double defaultProfitPercent;  // e.g., 15.0
    private List<WorksiteMonthlyCostDTO> rows;
}
