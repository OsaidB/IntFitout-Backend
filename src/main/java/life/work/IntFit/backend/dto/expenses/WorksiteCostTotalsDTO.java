package life.work.IntFit.backend.dto.expenses;
// File: src/main/java/life/work/IntFit/backend/dto/costs/WorksiteCostTotalsDTO.java

import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter @Setter @Builder
@NoArgsConstructor @AllArgsConstructor
public class WorksiteCostTotalsDTO {
    private Long masterWorksiteId;

    private LocalDate startDate;
    private LocalDate endDate;

    private WorkersTotals workers; // hours + wages
    private ExtrasTotals extras;    // dated + general + total
    private InvoicesTotals invoices; // count + total

    private BigDecimal grandTotal; // workers.wages + extras.total + invoices.total

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class WorkersTotals {
        private BigDecimal hours;     // total allocated hours (sum of effectiveAllocated)
        private BigDecimal wages;     // ₪
    }

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class ExtrasTotals {
        private BigDecimal dated;     // ₪ (within range)
        private BigDecimal general;   // ₪ (isGeneral / no date)
        private BigDecimal total;     // ₪
    }

    @Getter @Setter @Builder
    @NoArgsConstructor @AllArgsConstructor
    public static class InvoicesTotals {
        private long count;           // number of invoices in range
        private BigDecimal total;     // ₪
    }
}
