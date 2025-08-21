package life.work.IntFit.backend.dto.weekly_payroll;

import lombok.*;
import java.time.LocalDate;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayrollWeekDTO {
    private Long id;
    private LocalDate weekStart;
    private LocalDate weekEnd;
    private String status;
    private double totalBase;
    private double totalOtPay;
    private double totalAdjustments;
    private double totalToPay;
    private List<PayrollLineDTO> lines;
}