package life.work.IntFit.backend.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import life.work.IntFit.backend.dto.billing.MonthlyCostsResponseDTO;
import life.work.IntFit.backend.dto.billing.WorksiteMonthlyCostDTO;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.repository.InvoiceRepository;
import life.work.IntFit.backend.repository.MasterWorksiteRepository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MonthlyCostService {

    private final InvoiceRepository invoiceRepository;
    private final MasterWorksiteRepository masterWorksiteRepository;

    @PersistenceContext
    private EntityManager em;

    @Transactional
    public MonthlyCostsResponseDTO calculateMonthlyCosts(String ym, Long masterWorksiteId, Double profitPercent) {
        YearMonth y = YearMonth.parse(ym);             // "YYYY-MM"
        LocalDate fromDate = y.atDay(1);
        LocalDate toDate   = y.plusMonths(1).atDay(1); // [from, to)

        Map<Long, BigDecimal> inv = loadInvoiceSums(fromDate.atStartOfDay(), toDate.atStartOfDay());
        Map<Long, BigDecimal> lab = loadLaborSums(fromDate, toDate);

        // Target set (single site or all with activity this month)
        Set<Long> targetIds = new LinkedHashSet<>();
        if (masterWorksiteId != null) {
            targetIds.add(masterWorksiteId);
        } else {
            targetIds.addAll(inv.keySet());
            targetIds.addAll(lab.keySet());
        }

        if (targetIds.isEmpty()) {
            return MonthlyCostsResponseDTO.builder()
                    .month(ym)
                    .defaultProfitPercent(nz(profitPercent, 15d))
                    .rows(Collections.emptyList())
                    .build();
        }

        // Names
        Map<Long, String> nameMap = masterWorksiteRepository.findAllById(targetIds).stream()
                .collect(Collectors.toMap(MasterWorksite::getId, MasterWorksite::getApprovedName));

        Double pp = nz(profitPercent, 15d);
        List<WorksiteMonthlyCostDTO> rows = targetIds.stream().map(id -> {
            BigDecimal invoices = inv.getOrDefault(id, bd0());
            BigDecimal labor    = lab.getOrDefault(id, bd0());
            BigDecimal total    = invoices.add(labor);

            BigDecimal suggested = total
                    .multiply(BigDecimal.valueOf(1 + pp / 100.0))
                    .setScale(0, RoundingMode.HALF_UP);

            return WorksiteMonthlyCostDTO.builder()
                    .masterWorksiteId(id)
                    .masterWorksiteName(nameMap.getOrDefault(id, "#" + id))
                    .invoicesCost(scale2(invoices))
                    .laborCost(scale2(labor))
                    .totalCost(scale2(total))
                    .profitPercent(pp)
                    .suggestedCharge(suggested)
                    .build();
        }).toList();

        return MonthlyCostsResponseDTO.builder()
                .month(ym)
                .defaultProfitPercent(pp)
                .rows(rows)
                .build();
    }

    private Map<Long, BigDecimal> loadInvoiceSums(LocalDateTime from, LocalDateTime to) {
        List<Object[]> rows = invoiceRepository.sumInvoicesByMasterBetween(from, to);
        Map<Long, BigDecimal> map = new HashMap<>();
        for (Object[] r : rows) {
            if (r[0] == null || r[1] == null) continue;
            Long id = ((Number) r[0]).longValue();
            BigDecimal sum = toBigDecimal(r[1]);
            map.put(id, scale2(sum));
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, BigDecimal> loadLaborSums(LocalDate from, LocalDate to) {
        String sql = """
        WITH day_counts AS (
          SELECT wa.date, wa.team_member_id, COUNT(*) AS site_count
          FROM work_assignments wa
          WHERE wa.date >= :from AND wa.date < :to
          GROUP BY wa.date, wa.team_member_id
        ),
        member_day_cost AS (
          SELECT
            wa.master_worksite_id AS mw_id,
            /* daily wage + OT hours * hourly base * multiplier */
            ( tm.daily_wage
              + COALESCE(ot.overtime_hours, 0) * (tm.daily_wage / :stdHours) * :otMultiplier
            ) AS day_cost,
            dc.site_count
          FROM work_assignments wa
          JOIN team_members tm
            ON tm.id = wa.team_member_id
          LEFT JOIN work_assignment_overtime ot
            ON ot.team_member_id = wa.team_member_id
           AND ot.date = wa.date
          JOIN day_counts dc
            ON dc.date = wa.date
           AND dc.team_member_id = wa.team_member_id
          WHERE wa.date >= :from AND wa.date < :to
        )
        SELECT mw_id, ROUND(SUM(day_cost / NULLIF(site_count,0)), 2) AS sum_labor
        FROM member_day_cost
        WHERE mw_id IS NOT NULL
        GROUP BY mw_id
    """;

        List<Object[]> rows = em.createNativeQuery(sql)
                .setParameter("from", from)
                .setParameter("to",   to)
                .setParameter("stdHours", 8.0)     // ← change if your standard day ≠ 8h
                .setParameter("otMultiplier", 1.0) // ← set to 1.25/1.5 if OT is premium
                .getResultList();

        Map<Long, BigDecimal> map = new HashMap<>();
        for (Object[] r : rows) {
            if (r[0] == null || r[1] == null) continue;
            Long id = ((Number) r[0]).longValue();
            BigDecimal sum = (r[1] instanceof BigDecimal bd) ? bd : BigDecimal.valueOf(((Number) r[1]).doubleValue());
            map.put(id, sum.setScale(2, RoundingMode.HALF_UP));
        }
        return map;
    }



    // helpers
    private static Double nz(Double v, Double def) { return v == null ? def : v; }
    private static BigDecimal toBigDecimal(Object o) {
        if (o instanceof BigDecimal bd) return bd;
        if (o instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return bd0();
    }
    private static BigDecimal bd0() { return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP); }
    private static BigDecimal scale2(BigDecimal bd) {
        return (bd == null ? bd0() : bd.setScale(2, RoundingMode.HALF_UP));
    }
}
