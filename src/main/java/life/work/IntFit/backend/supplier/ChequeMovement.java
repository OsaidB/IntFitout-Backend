package life.work.IntFit.backend.supplier;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One real change of custody, appended only when the movement actually happened.
 *
 * A client cheque passed on to the supplier produces two rows - CLIENT to US on receipt and
 * US to SUPPLIER on handover - so the chain stays readable. Nothing here is invented:
 * verifying a legacy cheque records what is known now and does not backfill a receipt date
 * that nobody witnessed.
 */
@Entity
@Table(name = "cheque_movement")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ChequeMovement {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bank_check_id", nullable = false)
    private Long bankCheckId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_holder", length = 20)
    private SupplierEnums.Holder fromHolder;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_holder", nullable = false, length = 20)
    private SupplierEnums.Holder toHolder;

    /** Free-text counterparty, e.g. the client name, when the holder enum is not enough. */
    @Column(name = "counterparty", length = 200)
    private String counterparty;

    @Column(name = "moved_on", nullable = false)
    private LocalDate movedOn;

    @Column(length = 500)
    private String note;

    @Column(name = "recorded_by", length = 120)
    private String recordedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
