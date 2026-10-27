package life.work.IntFit.backend.supplier;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * One real payment to AL-Eatimad, recorded exactly once.
 *
 * The ledger starts empty on purpose: no historical payment is imported and no opening
 * balance is created, so these totals describe what has been recorded here, not the
 * supplier's own account balance.
 *
 * A cheque-backed payment always carries the cheque's full face value. A partial
 * allocation means part of the payment has not been assigned to a worksite yet - never
 * that part of the cheque was handed over.
 */
@Entity
@Table(name = "supplier_payment",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_supplier_payment_active_check",
           columnNames = "active_bank_check_id"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SupplierPayment {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "supplier_name", nullable = false, length = 120)
    @Builder.Default
    private String supplierName = "AL-Eatimad";

    @Column(name = "payment_date", nullable = false)
    private LocalDate paymentDate;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplierEnums.Method method;

    /**
     * The cheque backing this payment, for the two cheque routes.
     *
     * This link is permanent evidence and is never cleared, not even when the payment is
     * reversed. "At most one ACTIVE use" is enforced by {@link #activeBankCheckId}, which
     * carries the same value only while the payment stands.
     */
    @Column(name = "bank_check_id")
    private Long bankCheckId;

    /**
     * Mirrors bankCheckId while the payment is active and is NULL once reversed. A unique
     * index on this column lets history keep its link while still allowing exactly one live
     * use of a cheque, including under concurrent requests.
     */
    @Column(name = "active_bank_check_id")
    private Long activeBankCheckId;

    @Column(length = 120)
    private String reference;

    @Column(length = 1000)
    private String notes;

    /** Set when the record was entered in error. The row, its allocations and history stay. */
    @Column(name = "reversed_at")
    private Instant reversedAt;

    @Column(name = "reversal_reason", length = 500)
    private String reversalReason;

    /** Free-text actor from the existing app; there is no role system to draw on. */
    @Column(name = "recorded_by", length = 120)
    private String recordedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    public boolean isReversed() { return reversedAt != null; }

    @PrePersist @PreUpdate
    void syncActiveLink() {
        if (createdAt == null) createdAt = Instant.now();
        this.activeBankCheckId = (reversedAt == null) ? bankCheckId : null;
    }
}
