package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.Invoice;
import life.work.IntFit.backend.repository.projection.InvoiceSumView;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Repository
public interface InvoiceRepository extends JpaRepository<Invoice, Long> {

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

    /**
     * Returns rows: [ master_worksite_id, sum_total ]
     * - Groups invoices by master worksite between [from, to]
     * - Uses i.total, falling back to i.net_total
     * - Filters to confirmed invoices only
     * - Works whether invoice has master_worksite_id directly
     *   or only worksite_id (joined to worksite.master_worksite_id)
     */
    @Query(value = """
    SELECT
        w.master_worksite_id AS master_id,
        ROUND(SUM(COALESCE(i.total, i.net_total)), 2) AS sum_total
    FROM invoice i
    LEFT JOIN worksites w ON w.id = i.worksite_id
    WHERE i.date >= :from AND i.date < :to
      AND w.master_worksite_id IS NOT NULL
    GROUP BY w.master_worksite_id
""", nativeQuery = true)
    List<Object[]> sumInvoicesByMasterBetween(
            @Param("from") LocalDateTime from,
            @Param("to")   LocalDateTime to
    );


    @EntityGraph(attributePaths = { "items", "worksite", "worksite.masterWorksite" })
    Optional<Invoice> findById(Long id);



    /**
     * Sum of invoice totals (coalesce(total, netTotal)) for a master worksite
     * strictly BEFORE the given datetime.
     */
    @Query("""
        select coalesce(sum(coalesce(i.total, i.netTotal)), 0)
        from Invoice i
        where i.worksite.masterWorksite.id = :masterId
          and i.date < :before
    """)
    BigDecimal sumTotalsBeforeMaster(
            @Param("masterId") Long masterWorksiteId,
            @Param("before") LocalDateTime before
    );

    /**
     * Invoices in [from, to) for statement building (ordered asc).
     * (We don't fetch items/materials here to keep it lightweight.)
     */
    @Query("""
        select i
        from Invoice i
        where i.worksite.masterWorksite.id = :masterId
          and i.date >= :from and i.date < :to
        order by i.date asc, i.id asc
    """)
    List<Invoice> findByMasterBetween(
            @Param("masterId") Long masterWorksiteId,
            @Param("from") LocalDateTime from,
            @Param("to")   LocalDateTime to
    );

    /**
     * All invoices up to and including asOf (for open-balance checks).
     */
    @Query("""
        select i
        from Invoice i
        where i.worksite.masterWorksite.id = :masterId
          and i.date <= :asOf
        order by i.date asc, i.id asc
    """)
    List<Invoice> findAllByMasterUpTo(
            @Param("masterId") Long masterWorksiteId,
            @Param("asOf") LocalDateTime asOf
    );


    // Earliest invoice under a given MasterWorksite (by i.date ASC)
    @EntityGraph(attributePaths = {"worksite", "worksite.masterWorksite"})
    Optional<Invoice> findFirstByWorksite_MasterWorksite_IdOrderByDateAsc(Long masterWorksiteId);

// (If your Invoice already has a direct masterWorksite field, you could instead use:)
// @EntityGraph(attributePaths = {"worksite", "worksite.masterWorksite"})
// Optional<Invoice> findFirstByMasterWorksite_IdOrderByDateAsc(Long masterWorksiteId);


    @Query("""
        select count(i) as count, coalesce(sum(coalesce(i.total, i.netTotal)), 0) as total
        from Invoice i
        where i.worksite.masterWorksite.id = :masterId
          and i.date >= :from and i.date < :to
    """)
    InvoiceSumView sumForMasterInRange(@Param("masterId") Long masterId,
                                       @Param("from") LocalDateTime from,
                                       @Param("to")   LocalDateTime to);
}
