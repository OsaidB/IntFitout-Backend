package life.work.IntFit.backend.service.expenses;

import life.work.IntFit.backend.dto.expenses.MasterWorksiteCostDetailsDTO;
import life.work.IntFit.backend.model.entity.ExtraCost;
import life.work.IntFit.backend.model.entity.Invoice;
import life.work.IntFit.backend.model.entity.WorkAssignmentOvertime;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.repository.*;
import life.work.IntFit.backend.repository.projection.AssignmentView;
import life.work.IntFit.backend.repository.projection.MemberDayCountView;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorksiteCostDetailsServiceImpl implements WorksiteCostDetailsService {

    private final MasterWorksiteRepository masterRepo;
    private final WorkAssignmentRepository assignmentRepo;
    private final WorkAssignmentOvertimeRepository overtimeRepo;
    private final InvoiceRepository invoiceRepo;
    private final ExtraCostRepository extraRepo;

    private static BigDecimal bd(Double v) {
        return v == null ? BigDecimal.ZERO : BigDecimal.valueOf(v);
    }

    private static BigDecimal bd(Object v) {
        if (v == null) return BigDecimal.ZERO;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Double d) return BigDecimal.valueOf(d);
        if (v instanceof Integer i) return BigDecimal.valueOf(i);
        if (v instanceof Long l) return BigDecimal.valueOf(l);
        return BigDecimal.ZERO;
    }

    private record Key(LocalDate date, Long memberId) {}

    @Override
    @Transactional(readOnly = true)
    public MasterWorksiteCostDetailsDTO getCostDetails(Long masterId, LocalDate start, LocalDate end) {
        MasterWorksite master = masterRepo.findById(masterId)
                .orElseThrow(() -> new RuntimeException("MasterWorksite not found: " + masterId));

        // -------------------------
        // 1) Assignments (master only) as light rows
        // -------------------------
        List<AssignmentView> masterAssignments =
                assignmentRepo.sliceByMasterAndRange(masterId, start, end);

        Set<Long> memberIdsSet = masterAssignments.stream()
                .map(AssignmentView::getTeamMemberId)
                .collect(Collectors.toSet());

        List<Long> memberIds = new ArrayList<>(memberIdsSet);

        // If no assignments, still return invoices/extras.
        Map<Key, Long> siteCountMap = new HashMap<>();
        Map<Key, Double> overtimeMap = new HashMap<>();

        if (!memberIds.isEmpty()) {
            // site-counts across ALL masters for those members (needed for correct allocation)
            List<MemberDayCountView> counts =
                    assignmentRepo.countSitesForMembersInRange(memberIds, start, end);

            for (MemberDayCountView c : counts) {
                siteCountMap.put(new Key(c.getDate(), c.getTeamMemberId()), c.getSiteCount());
            }

            // overtime for those members
            List<WorkAssignmentOvertime> overtimeRows =
                    overtimeRepo.findForMembersInRange(memberIds, start, end);

            for (WorkAssignmentOvertime o : overtimeRows) {
                overtimeMap.put(new Key(o.getDate(), o.getTeamMember().getId()),
                        o.getOvertimeHours() == null ? 0.0 : o.getOvertimeHours());
            }
        }

        // Prepare day buckets (guarantee all dates exist)
        LinkedHashMap<LocalDate, DayAcc> dayAcc = new LinkedHashMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            dayAcc.put(d, new DayAcc(d));
        }

        // Worker totals
        Map<Long, WorkerAcc> workerAcc = new HashMap<>();

        // Assignments → compute allocated hours + cost
        for (AssignmentView a : masterAssignments) {
            LocalDate d = a.getDate();
            Long memberId = a.getTeamMemberId();

            long siteCount = siteCountMap.getOrDefault(new Key(d, memberId), 1L);
            double overtime = overtimeMap.getOrDefault(new Key(d, memberId), 0.0);

            double effectiveHours = 8.0 + Math.max(0.0, overtime);
            double allocatedHoursDouble = effectiveHours / (double) siteCount;

            BigDecimal allocatedHours = BigDecimal.valueOf(allocatedHoursDouble);

            // Cost model:
            // hourlyRate = dailyWage / 8
            // cost = hourlyRate * allocatedHours
            BigDecimal dailyWage = bd(a.getDailyWage() == null ? null : a.getDailyWage().doubleValue());
            BigDecimal cost = dailyWage
                    .multiply(allocatedHours)
                    .divide(BigDecimal.valueOf(8), 4, RoundingMode.HALF_UP);

            DayAcc bucket = dayAcc.get(d);
            if (bucket != null) {
                bucket.assignmentsCount++;
                bucket.workers = bucket.workers.add(cost);
                bucket.workerLines.add(MasterWorksiteCostDetailsDTO.WorkerLite.builder()
                        .teamMemberId(memberId)
                        .name(a.getTeamMemberName())
                        .allocatedHours(allocatedHours.setScale(2, RoundingMode.HALF_UP))
                        .cost(cost.setScale(2, RoundingMode.HALF_UP))
                        .build());
            }

            WorkerAcc wa = workerAcc.computeIfAbsent(memberId,
                    id -> new WorkerAcc(memberId, a.getTeamMemberName(), a.getTeamMemberRole()));


            wa.totalCost = wa.totalCost.add(cost);
            wa.totalAllocatedHours = wa.totalAllocatedHours.add(allocatedHours);
            wa.days.add(d);
        }

        // -------------------------
        // 2) Invoices (master) in [start, end]
        // -------------------------
        LocalDateTime from = start.atStartOfDay();
        LocalDateTime to = end.plusDays(1).atStartOfDay();

        List<Invoice> invoices = invoiceRepo.findByMasterBetween(masterId, from, to);

        for (Invoice inv : invoices) {
            if (inv.getDate() == null) continue;
            LocalDate d = inv.getDate().toLocalDate();

            BigDecimal amount = bd(inv.getTotal());
            if (amount.compareTo(BigDecimal.ZERO) == 0) {
                amount = bd(inv.getNetTotal());
            }

            DayAcc bucket = dayAcc.get(d);
            if (bucket != null) {
                bucket.invoicesCount++;
                bucket.supplies = bucket.supplies.add(amount);
                bucket.invoices.add(MasterWorksiteCostDetailsDTO.InvoiceLite.builder()
                        .id(inv.getId())
                        .businessDate(d)
                        .total(amount.setScale(2, RoundingMode.HALF_UP))
                        .worksiteName(inv.getWorksiteName())
                        .pdfUrl(inv.getPdfUrl())
                        .build());
            }
        }

        // -------------------------
        // 3) Extras (dated + general)
        // -------------------------
        List<ExtraCost> datedExtras = extraRepo.findDatedInRange(masterId, start, end);
        for (ExtraCost e : datedExtras) {
            if (e.getCostDate() == null) continue;
            DayAcc bucket = dayAcc.get(e.getCostDate());
            if (bucket != null) {
                bucket.extras = bucket.extras.add(e.getAmount());
                bucket.extrasLines.add(toExtraRow(e));
            }
        }

        List<ExtraCost> generalExtras = extraRepo.findByMasterWorksiteIdAndIsGeneralTrueOrderByIdDesc(masterId);
        BigDecimal generalTotal = generalExtras.stream()
                .map(ExtraCost::getAmount)
                .filter(Objects::nonNull)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // -------------------------
        // 4) Build response
        // -------------------------
        List<MasterWorksiteCostDetailsDTO.DailyRow> dailyRows = dayAcc.values().stream()
                .map(DayAcc::toDto)
                .toList();

        BigDecimal suppliesTotal = dailyRows.stream().map(MasterWorksiteCostDetailsDTO.DailyRow::getSupplies)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal workersTotal = dailyRows.stream().map(MasterWorksiteCostDetailsDTO.DailyRow::getWorkers)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal extrasDatedTotal = dailyRows.stream().map(MasterWorksiteCostDetailsDTO.DailyRow::getExtras)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal grand = suppliesTotal.add(workersTotal).add(extrasDatedTotal).add(generalTotal);

        List<MasterWorksiteCostDetailsDTO.WorkerRow> workerRows = workerAcc.values().stream()
                .map(WorkerAcc::toDto)
                .sorted(Comparator.comparing(MasterWorksiteCostDetailsDTO.WorkerRow::getTotalCost).reversed())
                .toList();

        List<MasterWorksiteCostDetailsDTO.ExtraRow> generalExtraRows =
                generalExtras.stream().map(this::toExtraRow).toList();

        return MasterWorksiteCostDetailsDTO.builder()
                .masterWorksiteId(master.getId())
                .masterWorksiteName(master.getApprovedName())
                .start(start)
                .end(end)
                .totals(MasterWorksiteCostDetailsDTO.Totals.builder()
                        .supplies(suppliesTotal.setScale(2, RoundingMode.HALF_UP))
                        .workers(workersTotal.setScale(2, RoundingMode.HALF_UP))
                        .extrasDated(extrasDatedTotal.setScale(2, RoundingMode.HALF_UP))
                        .extrasGeneral(generalTotal.setScale(2, RoundingMode.HALF_UP))
                        .grandTotal(grand.setScale(2, RoundingMode.HALF_UP))
                        .build())
                .daily(dailyRows)
                .workers(workerRows)
                .generalExtras(generalExtraRows)
                .build();
    }

    private MasterWorksiteCostDetailsDTO.ExtraRow toExtraRow(ExtraCost e) {
        return MasterWorksiteCostDetailsDTO.ExtraRow.builder()
                .id(e.getId())
                .costDate(e.getCostDate())
                .amount(e.getAmount() == null ? BigDecimal.ZERO : e.getAmount().setScale(2, RoundingMode.HALF_UP))
                .description(e.getDescription())
                .general(e.isGeneral() || e.getCostDate() == null)
                .build();
    }

    // ---- internal accumulators ----

    private static class DayAcc {
        final LocalDate date;
        BigDecimal supplies = BigDecimal.ZERO;
        BigDecimal workers = BigDecimal.ZERO;
        BigDecimal extras = BigDecimal.ZERO;

        long invoicesCount = 0;
        long assignmentsCount = 0;

        List<MasterWorksiteCostDetailsDTO.InvoiceLite> invoices = new ArrayList<>();
        List<MasterWorksiteCostDetailsDTO.WorkerLite> workerLines = new ArrayList<>();
        List<MasterWorksiteCostDetailsDTO.ExtraRow> extrasLines = new ArrayList<>();

        DayAcc(LocalDate date) { this.date = date; }

        MasterWorksiteCostDetailsDTO.DailyRow toDto() {
            BigDecimal total = supplies.add(workers).add(extras);
            return MasterWorksiteCostDetailsDTO.DailyRow.builder()
                    .date(date)
                    .dayOfWeek(date.getDayOfWeek().toString())
                    .supplies(supplies.setScale(2, RoundingMode.HALF_UP))
                    .workers(workers.setScale(2, RoundingMode.HALF_UP))
                    .extras(extras.setScale(2, RoundingMode.HALF_UP))
                    .total(total.setScale(2, RoundingMode.HALF_UP))
                    .invoicesCount(invoicesCount)
                    .assignmentsCount(assignmentsCount)
                    .invoices(invoices)
                    .workerLines(workerLines)
                    .extrasLines(extrasLines)
                    .build();
        }
    }

    private static class WorkerAcc {
        final Long id;
        final String name;
        final String role;

        BigDecimal totalCost = BigDecimal.ZERO;
        BigDecimal totalAllocatedHours = BigDecimal.ZERO;
        Set<LocalDate> days = new HashSet<>();

        WorkerAcc(Long id, String name, String role) {
            this.id = id;
            this.name = name;
            this.role = role;
        }

        MasterWorksiteCostDetailsDTO.WorkerRow toDto() {
            return MasterWorksiteCostDetailsDTO.WorkerRow.builder()
                    .teamMemberId(id)
                    .name(name)
                    .role(role)
                    .totalCost(totalCost.setScale(2, RoundingMode.HALF_UP))
                    .totalAllocatedHours(totalAllocatedHours.setScale(2, RoundingMode.HALF_UP))
                    .daysWorked((long) days.size())
                    .build();
        }
    }
}
