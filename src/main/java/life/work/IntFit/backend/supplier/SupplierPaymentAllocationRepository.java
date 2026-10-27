package life.work.IntFit.backend.supplier;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** Allocation lines of a supplier payment. */
public interface SupplierPaymentAllocationRepository extends JpaRepository<SupplierPaymentAllocation, Long> {
    List<SupplierPaymentAllocation> findByPaymentId(Long paymentId);
    List<SupplierPaymentAllocation> findByPaymentIdIn(List<Long> paymentIds);
    Optional<SupplierPaymentAllocation> findByPaymentIdAndMasterWorksiteId(Long paymentId, Long masterWorksiteId);

    @Query("select coalesce(sum(a.amount), 0) from SupplierPaymentAllocation a where a.paymentId = :paymentId")
    BigDecimal sumForPayment(@Param("paymentId") Long paymentId);
}
