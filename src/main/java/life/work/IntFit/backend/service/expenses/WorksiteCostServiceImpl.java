// File: src/main/java/life/work/IntFit/backend/service/expenses/WorksiteCostServiceImpl.java
package life.work.IntFit.backend.service.expenses;

import life.work.IntFit.backend.dto.expenses.WorksiteCostTotalsDTO;
import life.work.IntFit.backend.model.entity.WorkAssignmentOvertime;
import life.work.IntFit.backend.repository.ExtraCostRepository;
import life.work.IntFit.backend.repository.InvoiceRepository;
import life.work.IntFit.backend.repository.WorkAssignmentOvertimeRepository;
import life.work.IntFit.backend.repository.WorkAssignmentRepository;
import life.work.IntFit.backend.repository.projection.AssignmentView;
import life.work.IntFit.backend.repository.projection.InvoiceSumView;
import life.work.IntFit.backend.repository.projection.SiteCountView;
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
    private final WorkAssignmentOvertimeRepository overtimeRepo;
    private final InvoiceRepository invoiceRepo;
    private final ExtraCostRepository extraRepo;

    private static final BigDecimal EIGHT = BigDecimal.valueOf(8);

    @Override
    public WorksiteCostTotalsDTO computeTotals(Long masterWorksiteId, LocalDate start, LocalDate end) {

        // =========================
        // Workers (match frontend)
        // =========================

        // A) rows only for THIS master
        List<AssignmentView> rows = assignmentRepo.sliceByMasterAndRange(masterWorksiteId, start, end);

        // B) site counts for ALL masters (date+member) to split hours/pay correctly
        List<SiteCountView> counts = assignmentRepo.siteCountsByRange(start, end);
        Map<String, Long> siteCountMap = new HashMap<>(counts.size() * 2);
        for (SiteCountView c : counts) {
            siteCountMap.put(key(c.getDate(), c.getTeamMemberId()), nvlLong(c.getSiteCount(), 1L));
        }

        // C) overtime map (date+member)
        List<WorkAssignmentOvertime> overtimeRows = overtimeRepo.findAllByDateBetween(start, end);
        Map<String, Double> overtimeMap = new HashMap<>(overtimeRows.size() * 2);
        for (WorkAssignmentOvertime o : overtimeRows) {
            double h = Optional.ofNullable(o.getOvertimeHours()).orElse(0d);
            if (h < 0d) h = 0d;
            overtimeMap.put(key(o.getDate(), o.getTeamMember().getId()), h);
        }

        BigDecimal totalHours = BigDecimal.ZERO;
        BigDecimal totalWages = BigDecimal.ZERO;

        for (AssignmentView r : rows) {
            LocalDate d = r.getDate();
            Long tmId = r.getTeamMemberId();

            long siteCount = Math.max(siteCountMap.getOrDefault(key(d, tmId), 1L), 1L);

            double ot = overtimeMap.getOrDefault(key(d, tmId), 0d);
            if (ot < 0d) ot = 0d;

            // allocatedHours = (8 + ot) / siteCount
            BigDecimal allocatedHours = BigDecimal.valueOf(8d + ot)
                    .divide(BigDecimal.valueOf(siteCount), 8, RoundingMode.HALF_UP);

            // dayPay = dailyWage * (8 + ot) / 8
            BigDecimal dailyWage = bd(r.getDailyWage());
            BigDecimal dayPay = dailyWage.multiply(
                    BigDecimal.valueOf(8d + ot).divide(EIGHT, 8, RoundingMode.HALF_UP)
            );

            // sharePay = dayPay / siteCount
            BigDecimal sharePay = dayPay.divide(BigDecimal.valueOf(siteCount), 8, RoundingMode.HALF_UP);

            totalHours = totalHours.add(allocatedHours);
            totalWages = totalWages.add(sharePay);
        }

        totalHours = totalHours.setScale(2, RoundingMode.HALF_UP);
        totalWages = money0(totalWages); // integer shekels

        // =========================
        // Invoices: [from, to)
        // =========================
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime toDT = end.plusDays(1).atStartOfDay();

        InvoiceSumView inv = invoiceRepo.sumForMasterInRange(masterWorksiteId, from, toDT);
        long invoiceCount = (inv != null && inv.getCount() != null) ? inv.getCount() : 0L;
        BigDecimal invoiceTotal = money0((inv != null && inv.getTotal() != null) ? inv.getTotal() : BigDecimal.ZERO);

        // =========================
        // Extras
        // =========================
        BigDecimal extrasDated = money0(nvl(extraRepo.sumDatedInRange(masterWorksiteId, start, end)));
        BigDecimal extrasGeneral = money0(nvl(extraRepo.sumGeneral(masterWorksiteId)));
        BigDecimal extrasTotal = extrasDated.add(extrasGeneral);

        // =========================
        // Grand total
        // =========================
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

    private static String key(LocalDate d, Long memberId) {
        return d + ":" + memberId;
    }

    private static long nvlLong(Long v, long def) {
        return v == null ? def : v;
    }

    private static BigDecimal nvl(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal bd(Double v) {
        return v == null ? BigDecimal.ZERO : BigDecimal.valueOf(v);
    }

    /**
     * money rounded to 0 decimals (shekels)
     */
    private static BigDecimal money0(BigDecimal v) {
        return nvl(v).setScale(0, RoundingMode.HALF_UP);
    }

}
