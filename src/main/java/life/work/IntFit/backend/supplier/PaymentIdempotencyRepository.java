package life.work.IntFit.backend.supplier;

import org.springframework.data.jpa.repository.JpaRepository;

/** One row per logical payment submission, keyed by the client-supplied idempotency key. */
public interface PaymentIdempotencyRepository extends JpaRepository<PaymentIdempotencyKey, String> {
}
