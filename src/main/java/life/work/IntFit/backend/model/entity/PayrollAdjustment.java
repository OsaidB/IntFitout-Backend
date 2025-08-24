package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

// PayrollAdjustment.java
@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Table(name = "payroll_adjustment")
public class PayrollAdjustment {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payroll_line_id", nullable = false)
    private PayrollLine payrollLine;

    @Column(nullable = false)
    private double amount;

    @Column(length = 500)
    private String note;
}
