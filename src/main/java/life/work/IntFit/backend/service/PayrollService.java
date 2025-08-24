package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.weekly_payroll.*;
import life.work.IntFit.backend.mapper.PayrollMapper;
import life.work.IntFit.backend.model.entity.*;
import life.work.IntFit.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import life.work.IntFit.backend.exception.error.BadRequestException;
import life.work.IntFit.backend.exception.error.ConflictException;
import life.work.IntFit.backend.exception.error.NotFoundException;


import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PayrollService {
    private final PayrollWeekRepository weekRepo;
    private final PayrollLineRepository lineRepo;
    private final PayrollAdjustmentRepository adjRepo; // kept, but not used in Option B
    private final WorkAssignmentRepository assignmentRepo;
    private final PayrollMapper mapper;

    // Helper: start Saturday, end Thursday
    public static LocalDate[] satThu(LocalDate anchor) {
        DayOfWeek dow = anchor.getDayOfWeek();                        // MON=1..SUN=7
        LocalDate start = anchor.minusDays((dow.getValue() + 1) % 7); // previous-or-same Saturday
        LocalDate end = start.plusDays(5);                            // Thursday
        return new LocalDate[]{ start, end };
    }

    @Transactional
    public PayrollWeekDTO generateOrLoad(LocalDate anchor) {
        var range = satThu(anchor);
        LocalDate start = range[0], end = range[1];

        var existing = weekRepo.findByWeekStart(start);
        if (existing.isPresent()) {
            return mapper.toDTO(existing.get());
        }

        var assignments = assignmentRepo.findByDateBetween(start, end);

        Map<Long, MemberAgg> agg = new HashMap<>();
        for (var a : assignments) {
            long mid = a.getTeamMember().getId();
            var m = agg.computeIfAbsent(mid, k -> new MemberAgg(a.getTeamMember().getName()));
            m.accept(a);
        }

        var week = PayrollWeek.builder()
                .weekStart(start)
                .weekEnd(end)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .status(PayrollWeek.Status.DRAFT)
                .build();

//        if (week.getLines() == null) week.setLines(new ArrayList<>()); // new entity => fine

        double tBase = 0, tOt = 0, tAdj = 0, tTotal = 0;

        for (var e : agg.entrySet()) {
            var mid = e.getKey();
            var m = e.getValue();

            double effectiveOtPay = m.otPayFromDailyRates(); // (dailyWage/8)*otHours per day, summed

            var line = PayrollLine.builder()
                    .teamMemberId(mid)
                    .teamMemberName(m.name)
                    .baseWages(m.totalBase)
                    .computedOtHours(m.totalOtHours)
                    .computedOtPay(effectiveOtPay)
                    .effectiveOtPay(effectiveOtPay)
                    .finalTotal(m.totalBase + effectiveOtPay)
                    .build();

            week.addLine(line); // sets back-reference & keeps JPA happy


            tBase  += num(line.getBaseWages());
            tOt    += num(line.getEffectiveOtPay());
            tTotal += num(line.getFinalTotal());
        }

        week.setTotalBase(tBase);
        week.setTotalOtPay(tOt);
        week.setTotalAdjustments(tAdj);
        week.setTotalToPay(tTotal);

        var saved = weekRepo.save(week);
        return mapper.toDTO(saved);
    }

    @Transactional
    public PayrollWeekDTO addAdjustment(Long weekId, Long lineId, double amount, String note) {
        if (amount == 0.0) {
            throw new BadRequestException("Amount must be non-zero");
        }

        var week = weekRepo.findById(weekId)
                .orElseThrow(() -> new NotFoundException("Week not found: " + weekId));
        if (week.getStatus() != PayrollWeek.Status.DRAFT) {
            throw new ConflictException("Week is not editable: " + week.getStatus());
        }

        var line = lineRepo.findById(lineId)
                .orElseThrow(() -> new NotFoundException("Line not found: " + lineId));
        if (!Objects.equals(line.getPayrollWeek().getId(), weekId)) {
            throw new ConflictException("Line does not belong to the given week");
        }

        // Build & link the child
        var adj = PayrollAdjustment.builder()
                .amount(amount)
                .note(note == null ? "" : note.trim())
                .build();
        line.addAdjustment(adj); // sets back-ref

        // 💡 Ensure the new child has a DB id before orphan-removal runs on the bag
        adjRepo.saveAndFlush(adj);

        // Recompute denormalized totals
        recalcLine(line);
        recalcWeek(week);

        week.setUpdatedAt(OffsetDateTime.now());
        var saved = weekRepo.saveAndFlush(week);

        return mapper.toDTO(saved);
    }

    @Transactional
    public PayrollWeekDTO setOtOverride(Long weekId, Long lineId, Double hours, String note) {
        var week = weekRepo.findById(weekId).orElseThrow();
        if (week.getStatus() != PayrollWeek.Status.DRAFT) {
            throw new ConflictException("Week is not editable: " + week.getStatus());
        }

        var line = lineRepo.findById(lineId).orElseThrow();
        if (!Objects.equals(line.getPayrollWeek().getId(), weekId)) {
            throw new ConflictException("Line does not belong to the given week");
        }

        line.setOtHoursOverride(hours);
        line.setOtOverrideNote(note);

        recalcLine(line);
        recalcWeek(week);

        week.setUpdatedAt(OffsetDateTime.now());
        return mapper.toDTO(weekRepo.saveAndFlush(week));   // single flush
    }

    @Transactional
    public PayrollWeekDTO finalizeWeek(Long weekId) {
        var week = weekRepo.findById(weekId).orElseThrow();
        week.setStatus(PayrollWeek.Status.FINALIZED);
        week.setUpdatedAt(OffsetDateTime.now());
        return mapper.toDTO(weekRepo.saveAndFlush(week));
    }

    private void recalcLine(PayrollLine l) {
        double computedOtHours = num(l.getComputedOtHours());
        double hourly = computedOtHours > 0 ? (num(l.getComputedOtPay()) / computedOtHours) : 0.0;

        double effectiveOtHours = (l.getOtHoursOverride() != null) ? l.getOtHoursOverride() : computedOtHours;
        l.setEffectiveOtPay(hourly * effectiveOtHours);

        var adjs = l.getAdjustments();
        double adjSum = (adjs == null) ? 0.0 : adjs.stream().mapToDouble(PayrollAdjustment::getAmount).sum();
        l.setAdjustmentsTotal(adjSum);

        l.setFinalTotal(num(l.getBaseWages()) + num(l.getEffectiveOtPay()) + adjSum);
    }

    private void recalcWeek(PayrollWeek w) {
        var lines = w.getLines();
        if (lines == null) {
            w.setTotalBase(0);
            w.setTotalOtPay(0);
            w.setTotalAdjustments(0);
            w.setTotalToPay(0);
            return;
        }
        double tBase = 0, tOt = 0, tAdj = 0, tTotal = 0;
        for (var l : lines) {
            tBase  += num(l.getBaseWages());
            tOt    += num(l.getEffectiveOtPay());
            tAdj   += num(l.getAdjustmentsTotal());
            tTotal += num(l.getFinalTotal());
        }
        w.setTotalBase(tBase);
        w.setTotalOtPay(tOt);
        w.setTotalAdjustments(tAdj);
        w.setTotalToPay(tTotal);
    }

    // --- helper aggregator (per member) ---
    @lombok.Data
    static class MemberAgg {
        final String name;
        final Map<LocalDate, DayAgg> days = new HashMap<>();
        double totalBase = 0;
        double totalOtHours = 0;

        void accept(WorkAssignment a) {
            LocalDate d = a.getDate();
            DayAgg day = days.computeIfAbsent(d, k -> new DayAgg());

            double wage;
            if (a.getTeamMember() != null && a.getTeamMember().getDailyWage() != null) {
                wage = a.getTeamMember().getDailyWage();
            } else {
                wage = safeGetNumber(a, "getTeamMemberDailyWage", "getDailyWage");
            }
            day.dailyWage = Math.max(day.dailyWage, wage);

            double ot = safeGetNumber(a, "getOvertimeHours", "getOvertime", "getOtHours");
            day.otHours = Math.max(day.otHours, ot);
        }

        double otPayFromDailyRates() {
            double sum = 0;
            totalBase = 0;
            totalOtHours = 0;
            for (DayAgg entry : days.values()) {
                totalBase    += entry.dailyWage;
                totalOtHours += entry.otHours;
                sum          += (entry.dailyWage / 8.0) * entry.otHours;
            }
            return sum;
        }

        @lombok.Data
        static class DayAgg {
            double dailyWage = 0;
            double otHours = 0;
        }
    }

    private static double safeGetNumber(Object target, String... getterNames) {
        for (String g : getterNames) {
            try {
                var m = target.getClass().getMethod(g);
                Object v = m.invoke(target);
                if (v instanceof Number) return ((Number) v).doubleValue();
            } catch (NoSuchMethodException ignored) {
            } catch (Exception ignoredToo) {
            }
        }
        return 0.0;
    }

    // in PayrollService
    private void persistNewAdjustments(PayrollLine line) {
        if (line.getAdjustments() == null) return;
        for (var a : line.getAdjustments()) {
            if (a != null && a.getId() == null) {
                // ensure it has a DB id so orphan check won't choke
                adjRepo.save(a);
            }
        }
    }


    private static double num(Double v) { return v == null ? 0.0 : v; }
}
