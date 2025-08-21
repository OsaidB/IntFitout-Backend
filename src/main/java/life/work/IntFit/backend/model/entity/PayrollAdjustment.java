package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Table(name = "payroll_adjustment")
public class PayrollAdjustment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_line_id", nullable = false)
    private PayrollLine payrollLine;

    // positive = add, negative = subtract
    @Column(nullable = false)
    private double amount;

    @Column(length = 500)
    private String note;
}
