package life.work.IntFit.backend.supplier;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

/** Append-only audit of every allocation add, change or removal. Nothing is overwritten. */
@Entity
@Table(name = "supplier_allocation_change")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AllocationChange {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "master_worksite_id", nullable = false)
    private Long masterWorksiteId;

    /** NULL when the allocation was created. */
    @Column(name = "old_amount", precision = 14, scale = 2)
    private BigDecimal oldAmount;

    /** NULL when the allocation was removed. */
    @Column(name = "new_amount", precision = 14, scale = 2)
    private BigDecimal newAmount;

    @Column(length = 500)
    private String reason;

    @Column(name = "changed_by", length = 120)
    private String changedBy;

    @Column(name = "changed_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant changedAt = Instant.now();
}
