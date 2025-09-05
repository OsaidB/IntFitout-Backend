package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.Invoice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long>, JpaSpecificationExecutor<Invoice> {

    // Eager bits for worksite/items/material when fetching by worksite
    @EntityGraph(attributePaths = {"worksite", "items", "items.material"})
    List<Invoice> findByWorksiteId(Long worksiteId);

    // Recent invoices with eager graph (you already had this)
    @Query("""
    SELECT DISTINCT i FROM Invoice i
    LEFT JOIN FETCH i.worksite
    LEFT JOIN FETCH i.items items
    LEFT JOIN FETCH items.material
    ORDER BY i.date DESC
    """)
    List<Invoice> findRecentInvoices(Pageable pageable);

    // Latest invoice entity by business datetime (keeps time)
    Optional<Invoice> findTopByOrderByDateDesc();

    // ✅ Replace DATE() equality with a safe "between day" query (keeps time in DB, just filters by day window)
    @EntityGraph(attributePaths = {"items", "items.material", "worksite"})
    List<Invoice> findByDateBetween(LocalDateTime startInclusive, LocalDateTime endExclusive);

    // Your existing random-invoice-url by material (kept as-is)
    @Query("""
    SELECT i.pdfUrl
    FROM Invoice i
    JOIN InvoiceItem item ON item.invoice.id = i.id
    WHERE item.material.id = :materialId AND i.pdfUrl IS NOT NULL
    ORDER BY FUNCTION('RAND')
    LIMIT 1
    """)
    Optional<String> findRandomInvoiceUrlByMaterial(@Param("materialId") Long materialId);


}
