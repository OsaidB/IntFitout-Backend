package life.work.IntFit.backend.supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Cross-worksite aggregates for the tracker.
 *
 * Supplies reuse the same path the rest of the app already trusts: an invoice reaches a
 * master worksite only through its sub-worksite (invoice -> worksites.master_worksite_id).
 * There is no direct invoice-to-master column; both existing aggregates agree on this, so
 * this one matches them rather than inventing a second rule. Totals are all-time, and
 * returns already applied as negative invoices are netted automatically.
 */
@Repository
public class SupplierAggregateDao {

    private final JdbcTemplate jdbc;

    public SupplierAggregateDao(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * Latest qualifying activity per master worksite: work assignments or invoices.
     * This mirrors the Sites "active this week" definition. Metadata edits and supplier
     * payments are deliberately absent - recording a payment is not evidence that site
     * work resumed.
     */
    public Map<Long, LocalDate> lastActivityByMaster() {
        String sql = """
            SELECT master_id, MAX(d) AS last_d FROM (
                SELECT a.master_worksite_id AS master_id, a.date AS d
                  FROM work_assignments a
                 WHERE a.master_worksite_id IS NOT NULL
                UNION ALL
                SELECT w.master_worksite_id AS master_id, CAST(i.date AS DATE) AS d
                  FROM invoice i
                  JOIN worksites w ON w.id = i.worksite_id
                 WHERE w.master_worksite_id IS NOT NULL
            ) t
            GROUP BY master_id
            """;
        Map<Long, LocalDate> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(sql)) {
            Long id = toLong(r.get("master_id"));
            java.sql.Date d = (java.sql.Date) r.get("last_d");
            if (id != null && d != null) out.put(id, d.toLocalDate());
        }
        return out;
    }

    /** All-time supplies per master worksite, regardless of date. */
    public Map<Long, BigDecimal> suppliesByMaster() {
        String sql = """
            SELECT w.master_worksite_id AS master_id,
                   COALESCE(SUM(COALESCE(i.total, i.net_total)), 0) AS total
              FROM invoice i
              JOIN worksites w ON w.id = i.worksite_id
             WHERE w.master_worksite_id IS NOT NULL
             GROUP BY w.master_worksite_id
            """;
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(sql)) {
            Long id = toLong(r.get("master_id"));
            if (id != null) out.put(id, toMoney(r.get("total")));
        }
        return out;
    }

    /**
     * Supplies that cannot reach a master: the invoice has no worksite, or its worksite has
     * no master. Shown separately so the money stays visible instead of silently vanishing.
     */
    public BigDecimal unassignedSuppliesTotal() {
        String sql = """
            SELECT COALESCE(SUM(COALESCE(i.total, i.net_total)), 0) AS total
              FROM invoice i
              LEFT JOIN worksites w ON w.id = i.worksite_id
             WHERE i.worksite_id IS NULL OR w.id IS NULL OR w.master_worksite_id IS NULL
            """;
        return toMoney(jdbc.queryForList(sql).get(0).get("total"));
    }

    /** Per sub-worksite breakdown of the unassigned bucket, so it can be acted on. */
    public Map<String, BigDecimal> unassignedSuppliesByWorksite() {
        String sql = """
            SELECT COALESCE(CAST(i.worksite_id AS CHAR), 'none') AS k,
                   COALESCE(SUM(COALESCE(i.total, i.net_total)), 0) AS total
              FROM invoice i
              LEFT JOIN worksites w ON w.id = i.worksite_id
             WHERE i.worksite_id IS NULL OR w.id IS NULL OR w.master_worksite_id IS NULL
             GROUP BY COALESCE(CAST(i.worksite_id AS CHAR), 'none')
            """;
        Map<String, BigDecimal> out = new LinkedHashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(sql)) {
            out.put(String.valueOf(r.get("k")), toMoney(r.get("total")));
        }
        return out;
    }

    /**
     * All-time client receipts per master worksite, read from ar_payments.
     *
     * Display only. Receipts never offset supplies and are never written by this feature;
     * handing a client cheque to the supplier must not produce a second receipt.
     */
    public Map<Long, BigDecimal> receiptsByMaster() {
        String sql = """
            SELECT p.master_worksite_id AS master_id, COALESCE(SUM(p.amount), 0) AS total
              FROM ar_payments p
             WHERE p.master_worksite_id IS NOT NULL
             GROUP BY p.master_worksite_id
            """;
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(sql)) {
            Long id = toLong(r.get("master_id"));
            if (id != null) out.put(id, toMoney(r.get("total")));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Financial-period aggregates.
    //
    // Supplies are dated by invoice.date, the supplier's own transaction time, never by
    // parsedAt: in live data an invoice is imported a median of 6 hours and sometimes 3 days
    // after the supply, so parsedAt would file a Thursday delivery into the following week.
    // A late import therefore changes a past period's figures after the fact, which is
    // correct rather than a fault.
    //
    // Payments are dated by payment_date, the day money actually moved or a cheque changed
    // hands, never by the row's creation time and never by a cheque's due date.
    //
    // Ranges are half-open [from 00:00, toExclusive 00:00) on business wall-clock dates, so
    // a record at 23:59 on the last day is inside and midnight does not land in two periods.
    // ------------------------------------------------------------------

    /** Supplies per master in a period. May be negative where applied returns exceed supply. */
    public Map<Long, BigDecimal> suppliesByMasterBetween(LocalDate from, LocalDate toInclusive) {
        String sql = """
            SELECT w.master_worksite_id AS master_id,
                   COALESCE(SUM(COALESCE(i.total, i.net_total)), 0) AS total
              FROM invoice i
              JOIN worksites w ON w.id = i.worksite_id
             WHERE w.master_worksite_id IS NOT NULL
               AND i.date >= ? AND i.date < ?
             GROUP BY w.master_worksite_id
            """;
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, startTs(from), endTsExclusive(toInclusive))) {
            Long id = toLong(r.get("master_id"));
            if (id != null) out.put(id, toMoney(r.get("total")));
        }
        return out;
    }

    /**
     * Masters with at least one invoice row in the period.
     *
     * Deliberately counts ROWS, not a net total: a delivery and an applied return can cancel
     * to zero while the week genuinely had activity, and such a project must still be listed.
     */
    public Set<Long> mastersWithSupplyRecordsBetween(LocalDate from, LocalDate toInclusive) {
        String sql = """
            SELECT DISTINCT w.master_worksite_id AS master_id
              FROM invoice i
              JOIN worksites w ON w.id = i.worksite_id
             WHERE w.master_worksite_id IS NOT NULL
               AND i.date >= ? AND i.date < ?
            """;
        return idSet(sql, startTs(from), endTsExclusive(toInclusive));
    }

    /**
     * Allocated to each master in a period: the project's SHARE of payments made then, not
     * the payments' full amounts. A 10,000 payment split 6,000 / 3,000 contributes exactly
     * those shares, and its 1,000 remainder belongs to no project.
     */
    public Map<Long, BigDecimal> allocatedByMasterBetween(LocalDate from, LocalDate toInclusive) {
        String sql = """
            SELECT a.master_worksite_id AS master_id, COALESCE(SUM(a.amount), 0) AS total
              FROM supplier_payment_allocation a
              JOIN supplier_payment p ON p.id = a.payment_id
             WHERE p.reversed_at IS NULL
               AND p.payment_date >= ? AND p.payment_date <= ?
             GROUP BY a.master_worksite_id
            """;
        Map<Long, BigDecimal> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, from, toInclusive)) {
            Long id = toLong(r.get("master_id"));
            if (id != null) out.put(id, toMoney(r.get("total")));
        }
        return out;
    }

    /** Masters with at least one allocation row from a payment made in the period. */
    public Set<Long> mastersWithPaymentRecordsBetween(LocalDate from, LocalDate toInclusive) {
        String sql = """
            SELECT DISTINCT a.master_worksite_id AS master_id
              FROM supplier_payment_allocation a
              JOIN supplier_payment p ON p.id = a.payment_id
             WHERE p.reversed_at IS NULL
               AND p.payment_date >= ? AND p.payment_date <= ?
            """;
        return idSet(sql, from, toInclusive);
    }

    /**
     * What was actually handed to the supplier in the period, counting each payment once.
     *
     * This is a supplier-wide figure and is NOT the sum of project shares: it includes money
     * not yet assigned to any project, and it never double-counts a payment split across
     * several projects.
     */
    public BigDecimal actualPaidBetween(LocalDate from, LocalDate toInclusive) {
        String sql = """
            SELECT COALESCE(SUM(p.amount), 0) AS total
              FROM supplier_payment p
             WHERE p.reversed_at IS NULL
               AND p.payment_date >= ? AND p.payment_date <= ?
            """;
        return toMoney(jdbc.queryForList(sql, from, toInclusive).get(0).get("total"));
    }

    /** Of the money paid in the period, how much is still assigned to no project. */
    public BigDecimal unallocatedPaidBetween(LocalDate from, LocalDate toInclusive) {
        String sql = """
            SELECT COALESCE(SUM(p.amount), 0) - COALESCE((
                       SELECT SUM(a.amount) FROM supplier_payment_allocation a
                        JOIN supplier_payment p2 ON p2.id = a.payment_id
                       WHERE p2.reversed_at IS NULL
                         AND p2.payment_date >= ? AND p2.payment_date <= ?), 0) AS total
              FROM supplier_payment p
             WHERE p.reversed_at IS NULL
               AND p.payment_date >= ? AND p.payment_date <= ?
            """;
        return toMoney(jdbc.queryForList(sql, from, toInclusive, from, toInclusive).get(0).get("total"));
    }

    /** Supplies in the period that reach no master worksite. Separate from unallocated money. */
    public BigDecimal unassignedSuppliesBetween(LocalDate from, LocalDate toInclusive) {
        String sql = """
            SELECT COALESCE(SUM(COALESCE(i.total, i.net_total)), 0) AS total
              FROM invoice i
              LEFT JOIN worksites w ON w.id = i.worksite_id
             WHERE (i.worksite_id IS NULL OR w.id IS NULL OR w.master_worksite_id IS NULL)
               AND i.date >= ? AND i.date < ?
            """;
        return toMoney(jdbc.queryForList(sql, startTs(from), endTsExclusive(toInclusive)).get(0).get("total"));
    }

    /** Approved name, nickname and notes, where this business keeps alternative spellings. */
    public Map<Long, String[]> searchableMasterText() {
        Map<Long, String[]> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT id, approved_name, nickname, notes FROM master_worksites")) {
            Long id = toLong(r.get("id"));
            if (id == null) continue;
            out.put(id, new String[]{
                    str(r.get("approved_name")), str(r.get("nickname")), str(r.get("notes")) });
        }
        return out;
    }

    private Set<Long> idSet(String sql, Object... args) {
        Set<Long> out = new java.util.LinkedHashSet<>();
        for (Map<String, Object> r : jdbc.queryForList(sql, args)) {
            Long id = toLong(r.get("master_id"));
            if (id != null) out.add(id);
        }
        return out;
    }

    private static String str(Object v) { return v == null ? "" : String.valueOf(v); }

    /** invoice.date is a wall-clock LocalDateTime, so business dates bound it directly. */
    private static java.sql.Timestamp startTs(LocalDate d) {
        return java.sql.Timestamp.valueOf(d.atStartOfDay());
    }

    private static java.sql.Timestamp endTsExclusive(LocalDate inclusiveEnd) {
        return java.sql.Timestamp.valueOf(inclusiveEnd.plusDays(1).atStartOfDay());
    }

    /**
     * Which of these master worksite ids actually exist.
     *
     * Allocations name a master directly, so without this a typo or a stale client could
     * attach money to a project that does not exist and quietly strand it: the row would
     * never appear in any list, because every list is driven by real masters.
     */
    public java.util.Set<Long> existingMasterIds(java.util.Collection<Long> ids) {
        java.util.Set<Long> wanted = new java.util.LinkedHashSet<>();
        for (Long id : ids) if (id != null) wanted.add(id);
        if (wanted.isEmpty()) return java.util.Set.of();
        String marks = String.join(",", java.util.Collections.nCopies(wanted.size(), "?"));
        java.util.List<Long> found = jdbc.queryForList(
                "SELECT id FROM master_worksites WHERE id IN (" + marks + ")",
                Long.class, wanted.toArray());
        return new java.util.LinkedHashSet<>(found);
    }

    public Map<Long, String> masterNames() {
        Map<Long, String> out = new HashMap<>();
        for (Map<String, Object> r : jdbc.queryForList(
                "SELECT id, approved_name FROM master_worksites")) {
            Long id = toLong(r.get("id"));
            if (id != null) out.put(id, String.valueOf(r.get("approved_name")));
        }
        return out;
    }

    private static Long toLong(Object v) { return v == null ? null : ((Number) v).longValue(); }

    private static BigDecimal toMoney(Object v) {
        if (v == null) return BigDecimal.ZERO.setScale(2);
        BigDecimal b = (v instanceof BigDecimal d) ? d : new BigDecimal(v.toString());
        return b.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
