// File: life/work/IntFit/backend/repository/ArPaymentAllocationRepository.java
package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.ArPaymentAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface ArPaymentAllocationRepository extends JpaRepository<ArPaymentAllocation, Long> {

    @Query("select coalesce(sum(a.amount), 0) from ArPaymentAllocation a where a.invoiceId = :invoiceId")
    BigDecimal sumAllocationsForInvoice(Long invoiceId);

    @Query("""
           select coalesce(sum(a.amount), 0)
           from ArPaymentAllocation a
             join a.payment p
           where a.invoiceId = :invoiceId
             and p.date <= :asOf
           """)
    BigDecimal sumAllocationsForInvoiceAsOf(Long invoiceId, LocalDate asOf);
}
