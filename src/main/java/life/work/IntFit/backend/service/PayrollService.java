package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.weekly_payroll.*;
import life.work.IntFit.backend.mapper.PayrollMapper;
import life.work.IntFit.backend.model.entity.*;
import life.work.IntFit.backend.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
public class PayrollService {
    private final PayrollWeekRepository weekRepo;
    private final PayrollLineRepository lineRepo;
    private final PayrollAdjustmentRepository adjRepo;
    private final WorkAssignmentRepository assignmentRepo;
    private final PayrollMapper mapper;

    // Helper: start Saturday, end Thursday
    public static LocalDate[] satThu(LocalDate anchor) {
        DayOfWeek dow = anchor.getDayOfWeek();                      // MON=1..SUN=7
        LocalDate start = anchor.minusDays((dow.getValue() + 1) % 7); // previous-or-same Saturday
        LocalDate end = start.plusDays(5);                            // Thursday
        return new LocalDate[]{ start, end };
    }

    @Transactional
    public PayrollWeekDTO generateOrLoad(LocalDate anchor) {
        var range = satThu(anchor);
        LocalDate start = range[0], end = range[1];

        // Return existing if exists
        var existing = weekRepo.findByWeekStart(start);
        if (existing.isPresent()) {
            return mapper.toDTO(existing.get());
        }

        // Compute from assignments
        var assignments = assignmentRepo.findByDateBetween(start, end);

        // Per-member, per day: take max wage and max OT for that day, then sum across days
        Map<Long, MemberAgg> agg = new HashMap<>();
        for (var a : assignments) {
            long mid = a.getTeamMember().getId();
            var m = agg.computeIfAbsent(mid, k -> new MemberAgg(a.getTeamMember().getName()));
            m.accept(a);
        }

        // Build week entity
        var week = PayrollWeek.builder()
                .weekStart(start)
                .weekEnd(end)
                .createdAt(OffsetDateTime.now())
                .updatedAt(OffsetDateTime.now())
                .status(PayrollWeek.Status.DRAFT)
                .build();

        if (week.getLines() == null) week.setLines(new ArrayList<>());

        double tBase = 0, tOt = 0, tAdj = 0, tTotal = 0;

        for (var e : agg.entrySet()) {
            var mid = e.getKey();
            var m = e.getValue();

            double effectiveOtPay = m.otPayFromDailyRates(); // (dailyWage/8)*otHours summed per day

            var line = PayrollLine.builder()
                    .payrollWeek(week)
                    .teamMemberId(mid)
                    .teamMemberName(m.name)
                    .baseWages(m.totalBase)
                    .computedOtHours(m.totalOtHours)
                    .computedOtPay(effectiveOtPay)
                    .effectiveOtPay(effectiveOtPay)                  // no overrides yet
                    .finalTotal(m.totalBase + effectiveOtPay)         // no adjustments yet
                    .build();

            // make sure adjustments list exists if builder didn't set it
            if (line.getAdjustments() == null) line.setAdjustments(new ArrayList<>());

            week.getLines().add(line);
            tBase += num(line.getBaseWages());
            tOt   += num(line.getEffectiveOtPay());
            tTotal+= num(line.getFinalTotal());
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
        var week = weekRepo.findById(weekId).orElseThrow();
        var line = week.getLines().stream()
                .filter(l -> l.getId().equals(lineId))
                .findFirst()
                .orElseThrow();

        if (line.getAdjustments() == null) line.setAdjustments(new ArrayList<>());

        var adj = PayrollAdjustment.builder()
                .payrollLine(line)
                .amount(amount)
                .note(note)
                .build();

        line.getAdjustments().add(adj);

        // recalc line & week
        recalcLine(line);
        recalcWeek(week);

        week.setUpdatedAt(OffsetDateTime.now());
        return mapper.toDTO(weekRepo.save(week));
    }

    @Transactional
    public PayrollWeekDTO setOtOverride(Long weekId, Long lineId, Double hours, String note) {
        var week = weekRepo.findById(weekId).orElseThrow();
        var line = week.getLines().stream()
                .filter(l -> l.getId().equals(lineId))
                .findFirst()
                .orElseThrow();

        line.setOtHoursOverride(hours);
        line.setOtOverrideNote(note);

        recalcLine(line);
        recalcWeek(week);

        week.setUpdatedAt(OffsetDateTime.now());
        return mapper.toDTO(weekRepo.save(week));
    }

    @Transactional
    public PayrollWeekDTO finalizeWeek(Long weekId) {
        var week = weekRepo.findById(weekId).orElseThrow();
        week.setStatus(PayrollWeek.Status.FINALIZED);
        week.setUpdatedAt(OffsetDateTime.now());
        return mapper.toDTO(weekRepo.save(week));
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
            tBase += num(l.getBaseWages());
            tOt   += num(l.getEffectiveOtPay());
            tAdj  += num(l.getAdjustmentsTotal());
            tTotal+= num(l.getFinalTotal());
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
        // per-day max wage and per-day max OT; then sum
        final Map<LocalDate, DayAgg> days = new HashMap<>();
        double totalBase = 0;
        double totalOtHours = 0;

        void accept(WorkAssignment a) {
            LocalDate d = a.getDate(); // assumes LocalDate in your entity
            DayAgg day = days.computeIfAbsent(d, k -> new DayAgg());

            // Daily wage comes from the TeamMember (source of truth)
            double wage;
            if (a.getTeamMember() != null && a.getTeamMember().getDailyWage() != null) {
                wage = a.getTeamMember().getDailyWage();
            } else {
                // optional fallback if WorkAssignment stores a wage copy
                wage = safeGetNumber(a, "getTeamMemberDailyWage", "getDailyWage");
            }
            day.dailyWage = Math.max(day.dailyWage, wage);

            // OT: try several likely getter names; default 0 if none exist
            double ot = safeGetNumber(a, "getOvertimeHours", "getOvertime", "getOtHours");
            day.otHours = Math.max(day.otHours, ot);
        }

        double otPayFromDailyRates() {
            double sum = 0;
            totalBase = 0;
            totalOtHours = 0;
            for (DayAgg entry : days.values()) {
                totalBase   += entry.dailyWage;                 // one daily wage per worked day
                totalOtHours+= entry.otHours;                   // max OT for that day
                sum         += (entry.dailyWage / 8.0) * entry.otHours; // day-specific hourly
            }
            return sum;
        }

        @lombok.Data
        static class DayAgg {
            double dailyWage = 0;
            double otHours = 0;
        }
    }

    /** Safely call one of several numeric getters if present; returns 0.0 if none exist or null. */
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

    private static double num(Double v) { return v == null ? 0.0 : v; }
}
