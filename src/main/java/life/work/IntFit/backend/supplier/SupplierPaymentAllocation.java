package life.work.IntFit.backend.supplier;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** How much of one supplier payment is assigned to one master worksite. */
@Entity
@Table(name = "supplier_payment_allocation",
       uniqueConstraints = @UniqueConstraint(
           name = "uk_supplier_alloc_payment_master",
           columnNames = { "payment_id", "master_worksite_id" }))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SupplierPaymentAllocation {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "master_worksite_id", nullable = false)
    private Long masterWorksiteId;

    @Column(nullable = false, precision = 14, scale = 2)
    private BigDecimal amount;
}
