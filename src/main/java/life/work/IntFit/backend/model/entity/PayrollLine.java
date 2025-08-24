package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Table(name = "payroll_line")
public class PayrollLine {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "payroll_week_id", nullable = false)
    private PayrollWeek payrollWeek;

    // Snapshot of the employee
    private Long teamMemberId;
    private String teamMemberName;

    // Computed from assignments for Sat→Thu
    @Builder.Default private double baseWages = 0;
    @Builder.Default private double computedOtHours = 0;
    @Builder.Default private double computedOtPay = 0;

    // Editable
    private Double otHoursOverride;  // if not null, use this instead of computed
    private String otOverrideNote;

    // Denormalized effective OT pay stored for quick display
    @Builder.Default private double effectiveOtPay = 0;

    // Sum of adjustments (can be negative)
    @Builder.Default private double adjustmentsTotal = 0;

    // Final line total = baseWages + effectiveOtPay + adjustmentsTotal
    @Builder.Default private double finalTotal = 0;

    @OneToMany(
            mappedBy = "payrollLine",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    @Builder.Default
    @Setter(AccessLevel.NONE)              // <-- prevent replacing the list
    private List<PayrollAdjustment> adjustments = new ArrayList<>();

    public void addAdjustment(PayrollAdjustment a) {
        if (a == null) return;
        adjustments.add(a);
        a.setPayrollLine(this);
    }
    public void removeAdjustment(PayrollAdjustment a) {
        if (a == null) return;
        adjustments.remove(a);
        a.setPayrollLine(null);
    }
}