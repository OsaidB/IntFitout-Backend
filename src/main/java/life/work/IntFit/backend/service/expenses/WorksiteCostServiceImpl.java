// File: src/main/java/life/work/IntFit/backend/service/expenses/WorksiteCostServiceImpl.java
package life.work.IntFit.backend.service.expenses;

import life.work.IntFit.backend.dto.expenses.WorksiteCostTotalsDTO;
import life.work.IntFit.backend.model.entity.Invoice;
import life.work.IntFit.backend.model.entity.InvoiceItem;
import life.work.IntFit.backend.model.entity.WorkAssignmentOvertime;
import life.work.IntFit.backend.model.enums.MaterialCategory;
import life.work.IntFit.backend.repository.ExtraCostRepository;
import life.work.IntFit.backend.repository.InvoiceRepository;
import life.work.IntFit.backend.repository.WorkAssignmentOvertimeRepository;
import life.work.IntFit.backend.repository.WorkAssignmentRepository;
import life.work.IntFit.backend.repository.projection.AssignmentView;
import life.work.IntFit.backend.repository.projection.SiteCountView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    private enum Bucket { PAINTERS, GYPSUM, OTHER }

    @Override
    @Transactional(readOnly = true)
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

        // Labor split (for spentSplit)
        BigDecimal paintersWages = BigDecimal.ZERO;
        BigDecimal gypsumWages = BigDecimal.ZERO;
        BigDecimal otherWages = BigDecimal.ZERO;

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

            Bucket b = bucketFromRole(r.getTeamMemberRole());
            if (b == Bucket.PAINTERS) paintersWages = paintersWages.add(sharePay);
            else if (b == Bucket.GYPSUM) gypsumWages = gypsumWages.add(sharePay);
            else otherWages = otherWages.add(sharePay);
        }

        totalHours = totalHours.setScale(2, RoundingMode.HALF_UP);
        totalWages = money0(totalWages);

        paintersWages = money0(paintersWages);
        gypsumWages = money0(gypsumWages);
        otherWages = money0(otherWages);

        // =========================
        // Invoices + Supplies split (needs material.category)
        // Matches frontend:
        // - if items exist: sum per item total_price else qty*unit_price
        // - if no items: put invoice header total under "other"
        // =========================
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime toDT = end.plusDays(1).atStartOfDay();

        // This has EntityGraph: items + items.material + worksite
        List<Invoice> inRange = invoiceRepo.findByDateBetween(from, toDT);

        BigDecimal suppliesPainters = BigDecimal.ZERO;
        BigDecimal suppliesGypsum = BigDecimal.ZERO;
        BigDecimal suppliesOther = BigDecimal.ZERO;

        long invoiceCount = 0L;

        for (Invoice inv : (inRange == null ? List.<Invoice>of() : inRange)) {
            if (inv == null || inv.getWorksite() == null || inv.getWorksite().getMasterWorksite() == null) continue;
            if (!Objects.equals(inv.getWorksite().getMasterWorksite().getId(), masterWorksiteId)) continue;

            invoiceCount++;

            List<InvoiceItem> items = inv.getItems();
            if (items == null || items.isEmpty()) {
                // No items -> header goes to OTHER
                BigDecimal header = bd(inv.getTotal());
                if (header.compareTo(BigDecimal.ZERO) == 0) header = bd(inv.getNetTotal());
                suppliesOther = suppliesOther.add(header);
                continue;
            }

            for (InvoiceItem it : items) {
                BigDecimal lineTotal = extractItemTotal(it);

                MaterialCategory cat = MaterialCategory.OTHER;
                if (it != null && it.getMaterial() != null && it.getMaterial().getCategory() != null) {
                    cat = it.getMaterial().getCategory();
                }

                if (cat == MaterialCategory.PAINTING) suppliesPainters = suppliesPainters.add(lineTotal);
                else if (cat == MaterialCategory.GYPSUM) suppliesGypsum = suppliesGypsum.add(lineTotal);
                else suppliesOther = suppliesOther.add(lineTotal);
            }
        }

        suppliesPainters = money0(suppliesPainters);
        suppliesGypsum = money0(suppliesGypsum);
        suppliesOther = money0(suppliesOther);

        BigDecimal invoiceTotal = money0(suppliesPainters.add(suppliesGypsum).add(suppliesOther));

        // =========================
        // Extras
        // =========================
        BigDecimal extrasDated = money0(nvl(extraRepo.sumDatedInRange(masterWorksiteId, start, end)));
        BigDecimal extrasGeneral = money0(nvl(extraRepo.sumGeneral(masterWorksiteId)));
        BigDecimal extrasTotal = money0(extrasDated.add(extrasGeneral));

        // =========================
        // spentSplit (labor + supplies; extras go to OTHER like frontend)
        // =========================
        BigDecimal paintersSpent = money0(paintersWages.add(suppliesPainters));
        BigDecimal gypsumSpent = money0(gypsumWages.add(suppliesGypsum));
        BigDecimal otherSpent = money0(otherWages.add(suppliesOther).add(extrasTotal));

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
                .spentSplit(WorksiteCostTotalsDTO.SpentSplit.builder()
                        .painters(paintersSpent)
                        .gypsum(gypsumSpent)
                        .other(otherSpent)
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

    // Overload to support BOTH styles of fields in your entities/projections
    private static BigDecimal bd(Double v) {
        return v == null ? BigDecimal.ZERO : BigDecimal.valueOf(v);
    }
    private static BigDecimal bd(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private static BigDecimal extractItemTotal(InvoiceItem it) {
        if (it == null) return BigDecimal.ZERO;

        Double tp = it.getTotal_price();
        if (tp != null && Double.isFinite(tp)) return BigDecimal.valueOf(tp);

        double qty = Optional.ofNullable(it.getQuantity()).orElse(0d);
        double up = Optional.ofNullable(it.getUnit_price()).orElse(0d);
        double prod = qty * up;

        return Double.isFinite(prod) ? BigDecimal.valueOf(prod) : BigDecimal.ZERO;
    }

    private static Bucket bucketFromRole(String rawRole) {
        String raw = String.valueOf(rawRole == null ? "" : rawRole).trim().toLowerCase();
        if (raw.isEmpty()) return Bucket.OTHER;

        String noComma = raw.split(",")[0].trim();

        if (noComma.startsWith("painter") || noComma.startsWith("paint")) return Bucket.PAINTERS;
        if (noComma.startsWith("gypsum")) return Bucket.GYPSUM;

        return Bucket.OTHER;
    }

    /**
     * money rounded to 0 decimals (shekels)
     */
    private static BigDecimal money0(BigDecimal v) {
        return nvl(v).setScale(0, RoundingMode.HALF_UP);
    }
}
