package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.Invoice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import java.util.Optional;
import java.time.LocalDate;
@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

    @EntityGraph(attributePaths = {"worksite", "items", "items.material"})
    List<Invoice> findByWorksiteId(Long worksiteId);

    @Query("""
    SELECT DISTINCT i FROM Invoice i
    LEFT JOIN FETCH i.worksite
    LEFT JOIN FETCH i.items items
    LEFT JOIN FETCH items.material
    ORDER BY i.date DESC
    """)
    List<Invoice> findRecentInvoices(org.springframework.data.domain.Pageable pageable);

    Optional<Invoice> findTopByOrderByDateDesc();

    @EntityGraph(attributePaths = {"items", "items.material", "worksite"})
    @Query("""
    SELECT i FROM Invoice i
    WHERE FUNCTION('DATE', i.date) = :date
    """)
    List<Invoice> findByDate(@Param("date") LocalDate date);

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
