package life.work.IntFit.backend.supplier;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Append-only custody log for a cheque. */
public interface ChequeMovementRepository extends JpaRepository<ChequeMovement, Long> {
    List<ChequeMovement> findByBankCheckIdOrderByMovedOnAscIdAsc(Long bankCheckId);
}
