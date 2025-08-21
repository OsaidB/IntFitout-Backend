package life.work.IntFit.backend.dto.weekly_payroll;

import lombok.*;
import java.time.LocalDate;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PayrollAdjustmentDTO {
    private Long id;
    private double amount; // +/-
    private String note;
}