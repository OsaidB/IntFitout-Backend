package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.PendingInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;   // ✅ new
import java.util.List;
import java.util.Optional;
import java.time.LocalDate;


import org.springframework.data.jpa.repository.EntityGraph;

@Repository
public interface PendingInvoiceRepository extends JpaRepository<PendingInvoice, Long> {

    // Latest pending invoice entity by business datetime
    Optional<PendingInvoice> findTopByOrderByDateDesc();

    // Unconfirmed list helpers
    List<PendingInvoice> findByConfirmedFalse();

    List<PendingInvoice> findTop20ByOrderByParsedAtDesc();

    List<PendingInvoice> findByWorksiteIdAndConfirmedFalse(Long worksiteId);

    // Eager load items/materials
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

    // =========================
    //  LocalDate (date-only) helpers — keep for compatibility
    // =========================

    // Single latest business date among ALL pending invoices
    @Query("select max(p.date) from PendingInvoice p")
    LocalDate findLatestBusinessDateAll();

    // Single latest business date among UNCONFIRMED pending invoices
    @Query("select max(p.date) from PendingInvoice p where p.confirmed = false")
    LocalDate findLatestBusinessDateUnconfirmed();

    // Top-K latest business dates (ALL)
    @Query("select p.date from PendingInvoice p order by p.date desc")
    List<LocalDate> findLatestBusinessDates(Pageable pageable);

    // Top-K latest business dates (UNCONFIRMED)
    @Query("select p.date from PendingInvoice p where p.confirmed = false order by p.date desc")
    List<LocalDate> findLatestBusinessDatesUnconfirmed(Pageable pageable);

    // =========================
    //  LocalDateTime (with time) helpers — NEW
    // =========================

    // Single latest business datetime among ALL pending invoices
    @Query("select max(p.date) from PendingInvoice p")
    LocalDateTime findLatestBusinessDateTimeAll();

    // Single latest business datetime among UNCONFIRMED pending invoices
    @Query("select max(p.date) from PendingInvoice p where p.confirmed = false")
    LocalDateTime findLatestBusinessDateTimeUnconfirmed();

    // Top-K latest business datetimes (ALL)
    @Query("select p.date from PendingInvoice p order by p.date desc")
    List<LocalDateTime> findLatestBusinessDateTimes(Pageable pageable);

    // Top-K latest business datetimes (UNCONFIRMED)
    @Query("select p.date from PendingInvoice p where p.confirmed = false order by p.date desc")
    List<LocalDateTime> findLatestBusinessDateTimesUnconfirmed(Pageable pageable);
}
