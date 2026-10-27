package life.work.IntFit.backend.supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
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
