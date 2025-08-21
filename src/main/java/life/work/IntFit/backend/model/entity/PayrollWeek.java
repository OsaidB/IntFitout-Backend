package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@Table(
        name = "payroll_week",
        uniqueConstraints = @UniqueConstraint(columnNames = {"week_start"})
)
public class PayrollWeek {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "week_start", nullable = false) // Saturday (local)
    private LocalDate weekStart;

    @Column(name = "week_end", nullable = false)   // Thursday (local)
    private LocalDate weekEnd;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    private Status status = Status.DRAFT; // DRAFT, FINALIZED, PAID

    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // Optional snapshot totals (for fast listing)
    @Builder.Default private double totalBase = 0;
    @Builder.Default private double totalOtPay = 0;
    @Builder.Default private double totalAdjustments = 0;
    @Builder.Default private double totalToPay = 0;

    @OneToMany(mappedBy = "payrollWeek", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @Builder.Default
    private List<PayrollLine> lines = new ArrayList<>();

    public enum Status { DRAFT, FINALIZED, PAID }
}
