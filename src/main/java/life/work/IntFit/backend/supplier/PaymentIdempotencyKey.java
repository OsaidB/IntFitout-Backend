package life.work.IntFit.backend.supplier;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One row per logical payment submission.
 *
 * The client generates the key once for a submission and reuses it on every retry; a fresh
 * key per network attempt would defeat the protection entirely. Same key with the same body
 * returns the original payment, same key with a different body is refused.
 */
@Entity
@Table(name = "supplier_payment_idempotency")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PaymentIdempotencyKey {

    @Id
    @Column(name = "idempotency_key", length = 120, nullable = false)
    private String key;

    @Column(name = "request_hash", length = 64, nullable = false)
    private String requestHash;

    @Column(name = "payment_id", nullable = false)
    private Long paymentId;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
