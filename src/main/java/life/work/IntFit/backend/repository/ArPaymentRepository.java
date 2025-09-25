// File: life/work/IntFit/backend/repository/ArPaymentRepository.java
package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.ArPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.LocalDate;
import java.util.List;

public interface ArPaymentRepository extends JpaRepository<ArPayment, Long> {

    List<ArPayment> findByMasterWorksiteIdAndDateBetween(Long masterWorksiteId, LocalDate from, LocalDate to);

    @Query("""
           select coalesce(sum(p.amount), 0)
           from ArPayment p
           where p.masterWorksiteId = :masterWorksiteId
             and p.date < :beforeDate
           """)
    BigDecimal sumByMasterAndDateBefore(@Param("masterWorksiteId") Long masterWorksiteId,
                                        @Param("beforeDate") LocalDate beforeDate);

    @Query("select coalesce(sum(a.amount), 0) from ArPaymentAllocation a where a.payment.id = :paymentId")
    BigDecimal sumAllocationsForPayment(@Param("paymentId") Long paymentId);

    // ✅ NEW: fetch-join allocations to avoid lazy init later
    @Query("""
           select distinct p
           from ArPayment p
           left join fetch p.allocations a
           where p.masterWorksiteId = :masterId
             and p.date >= :from and p.date < :toExclusive
           order by p.date asc, p.id asc
           """)
    List<ArPayment> findWithAllocationsByMasterAndDateRange(@Param("masterId") Long masterWorksiteId,
                                                            @Param("from") LocalDate from,
                                                            @Param("toExclusive") LocalDate toExclusive);

    List<ArPayment> findByDateBetween(LocalDate from, LocalDate to);


}
