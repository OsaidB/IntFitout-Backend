package life.work.IntFit.backend.dto.billing;

import lombok.*;
import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorksiteMonthlyCostDTO {
    private Long masterWorksiteId;
    private String masterWorksiteName;

    private BigDecimal invoicesCost;   // ₪
    private BigDecimal laborCost;      // ₪
    private BigDecimal totalCost;      // invoices + labor

    private Double profitPercent;      // e.g., 15.0
    private BigDecimal suggestedCharge; // rounded
}
