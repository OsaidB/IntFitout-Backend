package life.work.IntFit.backend.supplier;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Supplier payments. The active link column is what enforces one live use of a cheque. */
public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, Long> {

    /** The one live use of a cheque, if any. Reversed rows keep their link but are not active. */
    Optional<SupplierPayment> findByActiveBankCheckId(Long bankCheckId);

    /** Every use ever, active or reversed - the cheque's payment history. */
    List<SupplierPayment> findByBankCheckIdOrderByIdAsc(Long bankCheckId);

    boolean existsByBankCheckId(Long bankCheckId);

    List<SupplierPayment> findByPaymentDateBetweenOrderByPaymentDateDescIdDesc(LocalDate from, LocalDate to);

    /** Serialises concurrent attempts to use the same payment row. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from SupplierPayment p where p.id = :id")
    Optional<SupplierPayment> lockById(@Param("id") Long id);
}
