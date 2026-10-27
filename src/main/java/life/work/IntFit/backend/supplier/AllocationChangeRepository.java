package life.work.IntFit.backend.supplier;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Append-only audit of allocation edits. */
public interface AllocationChangeRepository extends JpaRepository<AllocationChange, Long> {
    List<AllocationChange> findByPaymentIdOrderByIdAsc(Long paymentId);
}
