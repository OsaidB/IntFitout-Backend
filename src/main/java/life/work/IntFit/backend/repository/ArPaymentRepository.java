// File: life/work/IntFit/backend/repository/ArPaymentRepository.java
package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.ArPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ArPaymentRepository extends JpaRepository<ArPayment, Long> {

    List<ArPayment> findByMasterWorksiteIdAndDateBetween(Long masterWorksiteId, LocalDate from, LocalDate to);

    @Query("select coalesce(sum(p.amount), 0) from ArPayment p where p.masterWorksiteId = :masterWorksiteId and p.date < :beforeDate")
    BigDecimal sumByMasterAndDateBefore(Long masterWorksiteId, LocalDate beforeDate);

    @Query("select coalesce(sum(a.amount), 0) from ArPaymentAllocation a where a.payment.id = :paymentId")
    BigDecimal sumAllocationsForPayment(Long paymentId);

    // File: repository/ArPaymentRepository.java
// (add this extra finder)
    List<ArPayment> findByDateBetween(LocalDate from, LocalDate to);


}
