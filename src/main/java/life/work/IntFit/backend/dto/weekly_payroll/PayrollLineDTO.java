package life.work.IntFit.backend.dto.weekly_payroll;

import lombok.*;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayrollLineDTO {
    private Long id;
    private Long teamMemberId;
    private String teamMemberName;
    private double baseWages;
    private double computedOtHours;
    private double computedOtPay;
    private Double otHoursOverride;
    private String otOverrideNote;
    private double effectiveOtPay;
    private double adjustmentsTotal;
    private double finalTotal;
    private List<PayrollAdjustmentDTO> adjustments;
}