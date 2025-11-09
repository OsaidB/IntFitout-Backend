// File: src/main/java/life/work/IntFit/backend/service/impl/WorksiteCostServiceImpl.java
package life.work.IntFit.backend.service.expenses;

import life.work.IntFit.backend.dto.expenses.WorksiteCostTotalsDTO;
import life.work.IntFit.backend.model.entity.WorkAssignment;
import life.work.IntFit.backend.repository.ExtraCostRepository;
import life.work.IntFit.backend.repository.InvoiceRepository;
import life.work.IntFit.backend.repository.WorkAssignmentRepository;
import life.work.IntFit.backend.repository.projection.InvoiceSumView;
import life.work.IntFit.backend.service.expenses.WorksiteCostService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class WorksiteCostServiceImpl implements WorksiteCostService {

    private final WorkAssignmentRepository assignmentRepo;
    private final InvoiceRepository invoiceRepo;
    private final ExtraCostRepository extraRepo;

    private static final BigDecimal EIGHT = BigDecimal.valueOf(8);

    @Override
    public WorksiteCostTotalsDTO computeTotals(Long masterWorksiteId, LocalDate start, LocalDate end) {

        // -------- Workers: match frontend logic --------
        // 1) Load ALL assignments in range (across all worksites) to compute per-day site counts.
        final List<WorkAssignment> allInRange =
                assignmentRepo.findAllByDateBetween(start, end);

        // 2) Per-day, per-member site count across the whole company
        final Map<LocalDate, Map<Long, Long>> siteCountByDayMember = new HashMap<>();
        for (WorkAssignment a : allInRange) {
            if (a == null || a.getTeamMember() == null) continue;
            final LocalDate d = a.getDate();
            final Long memberId = a.getTeamMember().getId();
            siteCountByDayMember
                    .computeIfAbsent(d, k -> new HashMap<>())
                    .merge(memberId, 1L, Long::sum);
        }

        BigDecimal totalHours = BigDecimal.ZERO;
        BigDecimal totalWages = BigDecimal.ZERO;

        // 3) Cost only rows that BELONG to the requested master, but split hours using the global site count
        for (WorkAssignment a : allInRange) {
            if (a == null || a.getTeamMember() == null || a.getMasterWorksite() == null) continue;
            if (!Objects.equals(a.getMasterWorksite().getId(), masterWorksiteId)) continue;

            final LocalDate d = a.getDate();
            final Long memberId = a.getTeamMember().getId();

            final long cnt = Math.max(
                    siteCountByDayMember
                            .getOrDefault(d, Collections.emptyMap())
                            .getOrDefault(memberId, 1L),
                    1L
            );

            // Prefer per-assignment allocated hours if present; else 8 / siteCount
            BigDecimal allocated = reflectBD(a, "allocatedHours", "effectiveAllocated", "hoursAllocated", "allocated");
            BigDecimal hours = (allocated != null)
                    ? allocated
                    : EIGHT.divide(BigDecimal.valueOf(cnt), 6, RoundingMode.HALF_UP);

            // Prefer per-assignment wage override; else member's default
            BigDecimal wageOverride = reflectBD(a, "teamMemberDailyWage", "dailyWageAtAssignment", "assignedDailyWage");
            BigDecimal memberDefaultWage = toBD(a.getTeamMember().getDailyWage());
            BigDecimal dailyWage = (wageOverride != null) ? wageOverride : nvl(memberDefaultWage);

            BigDecimal hourly = dailyWage.divide(EIGHT, 6, RoundingMode.HALF_UP);
            BigDecimal cost = hours.multiply(hourly);

            totalHours = totalHours.add(hours);
            totalWages = totalWages.add(cost);
        }

        totalHours = totalHours.setScale(2, RoundingMode.HALF_UP);
        totalWages = money(totalWages); // round like UI (integer shekels)

        // -------- Invoices: [from, to) by LocalDateTime --------
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime toDT = end.plusDays(1).atStartOfDay();
        InvoiceSumView inv = invoiceRepo.sumForMasterInRange(masterWorksiteId, from, toDT);
        long invoiceCount = (inv != null && inv.getCount() != null) ? inv.getCount() : 0L;
        BigDecimal invoiceTotal = money((inv != null && inv.getTotal() != null) ? inv.getTotal() : BigDecimal.ZERO);

        // -------- Extras --------
        BigDecimal extrasDated   = money(nvl(extraRepo.sumDatedInRange(masterWorksiteId, start, end)));
        BigDecimal extrasGeneral = money(nvl(extraRepo.sumGeneral(masterWorksiteId)));
        BigDecimal extrasTotal   = extrasDated.add(extrasGeneral);

        // -------- Grand total --------
        BigDecimal grand = totalWages.add(invoiceTotal).add(extrasTotal);

        return WorksiteCostTotalsDTO.builder()
                .masterWorksiteId(masterWorksiteId)
                .startDate(start)
                .endDate(end)
                .workers(WorksiteCostTotalsDTO.WorkersTotals.builder()
                        .hours(totalHours)
                        .wages(totalWages)
                        .build())
                .extras(WorksiteCostTotalsDTO.ExtrasTotals.builder()
                        .dated(extrasDated)
                        .general(extrasGeneral)
                        .total(extrasTotal)
                        .build())
                .invoices(WorksiteCostTotalsDTO.InvoicesTotals.builder()
                        .count(invoiceCount)
                        .total(invoiceTotal)
                        .build())
                .grandTotal(grand)
                .build();
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal money(BigDecimal v) {
        return nvl(v).setScale(0, RoundingMode.HALF_UP);
    }
    private static BigDecimal toBD(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
        return new BigDecimal(v.toString());
    }


    /**
     * Reflection helper to read optional numeric fields that may not exist in your entity.
     * Tries getters like getAllocatedHours(), getEffectiveAllocated(), etc.
     */
    private static BigDecimal reflectBD(Object bean, String... props) {
        for (String p : props) {
            String getter = "get" + Character.toUpperCase(p.charAt(0)) + p.substring(1);
            try {
                Object v = bean.getClass().getMethod(getter).invoke(bean);
                if (v == null) continue;
                if (v instanceof BigDecimal bd) return bd;
                if (v instanceof Number n) return BigDecimal.valueOf(n.doubleValue());
                return new BigDecimal(v.toString());
            } catch (Exception ignore) {
                // no such getter or not accessible — try next alias
            }
        }
        return null;
    }
}
