package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "ar_payment_allocations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(exclude = "payment")
public class ArPaymentAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    // FK to ArPayment
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "payment_id", nullable = false)
    private ArPayment payment;

    // We only need invoice id (keep loose coupling)
    @Column(name = "invoice_id", nullable = false)
    private Long invoiceId;

    @Column(name = "amount", nullable = false, precision = 19, scale = 2)
    private BigDecimal amount;
}
