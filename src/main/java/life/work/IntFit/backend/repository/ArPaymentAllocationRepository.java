// File: life/work/IntFit/backend/repository/ArPaymentAllocationRepository.java
package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.ArPaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface ArPaymentAllocationRepository extends JpaRepository<ArPaymentAllocation, Long> {

    @Query("""
      select coalesce(sum(a.amount),0)
      from ArPaymentAllocation a
        join a.payment p
      where a.chargeId = :chargeId
        and p.date <= :asOfDate
    """)
    BigDecimal sumForChargeAsOf(@Param("chargeId") Long chargeId,
                                @Param("asOfDate") java.time.LocalDate asOfDate);

    boolean existsByPayment_Id(Long paymentId);
}
