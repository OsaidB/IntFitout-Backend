package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.PendingInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.time.LocalDate;


import org.springframework.data.jpa.repository.EntityGraph;

@Repository
public interface PendingInvoiceRepository extends JpaRepository<PendingInvoice, Long> {

    Optional<PendingInvoice> findTopByOrderByDateDesc();

    List<PendingInvoice> findByConfirmedFalse();

    List<PendingInvoice> findTop20ByOrderByParsedAtDesc();

    List<PendingInvoice> findByWorksiteIdAndConfirmedFalse(Long worksiteId);

    @Query("""
    SELECT DISTINCT pi FROM PendingInvoice pi
    LEFT JOIN FETCH pi.items items
    LEFT JOIN FETCH items.material
    ORDER BY pi.parsedAt DESC
    """)
    List<PendingInvoice> findAllWithItems();


    @Query("""
    SELECT DISTINCT pi FROM PendingInvoice pi
    LEFT JOIN FETCH pi.items items
    LEFT JOIN FETCH items.material
    WHERE pi.confirmed = false AND pi.totalMatch = false
    ORDER BY pi.parsedAt DESC
    """)
    List<PendingInvoice> findByConfirmedFalseAndTotalMatchFalse();

}
