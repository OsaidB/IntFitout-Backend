package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
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

    @OneToMany(
            mappedBy = "payrollWeek",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    @Builder.Default
    @Setter(AccessLevel.NONE) // <-- prevent replacing the list from outside
    private List<PayrollLine> lines = new ArrayList<>();

    public enum Status { DRAFT, FINALIZED, PAID }

    /* ===== Helper methods to manage bidirectional relationship safely ===== */

    /** Add a single line and set the back-reference. */
    public void addLine(PayrollLine line) {
        if (line == null) return;
        lines.add(line);
        line.setPayrollWeek(this);
    }

    /** Add many lines at once, ensuring back-references are set. */
    public void addLines(Collection<? extends PayrollLine> newLines) {
        if (newLines == null) return;
        for (PayrollLine l : newLines) {
            addLine(l);
        }
    }

    /** Remove a line and clear the back-reference (triggers orphanRemoval). */
    public void removeLine(PayrollLine line) {
        if (line == null) return;
        if (lines.remove(line)) {
            line.setPayrollWeek(null);
        }
    }

    /** Clear all lines safely (will delete orphans because orphanRemoval=true). */
    public void clearLines() {
        for (PayrollLine l : new ArrayList<>(lines)) {
            removeLine(l);
        }
    }

    /**
     * Controlled replacement: sync the collection without exposing a raw setter.
     * Old lines are removed (and orphaned), new ones are attached with back-refs.
     */
    public void replaceLines(Collection<? extends PayrollLine> newLines) {
        clearLines();
        addLines(newLines);
    }
}
