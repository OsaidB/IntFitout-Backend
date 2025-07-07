package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.Invoice;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import org.springframework.data.jpa.repository.Query;

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

}
