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
    private final PayrollAdjustmentRepository adjRepo;
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

        // 1) Load or create the week shell
        PayrollWeek week = weekRepo.findByWeekStart(start).orElseGet(() -> {
            return PayrollWeek.builder()
                    .weekStart(start)
                    .weekEnd(end)
                    .createdAt(OffsetDateTime.now())
                    .updatedAt(OffsetDateTime.now())
                    .status(PayrollWeek.Status.DRAFT)
                    .build();
        });

        // 2) If week is DRAFT, (up)sert lines from current assignments
        if (week.getStatus() == PayrollWeek.Status.DRAFT) {
            upsertLinesFromAssignments(week, start, end);   // 👈 new helper below
            recalcWeek(week);
            week.setUpdatedAt(OffsetDateTime.now());
            week = weekRepo.saveAndFlush(week);
        } else if (week.getWeekEnd() == null) {
            // minor hygiene if an old row is missing week_end
            week.setWeekEnd(end);
        }

        return mapper.toDTO(week);
    }

    /** Build/refresh lines from Sat→Thu assignments while preserving overrides & adjustments. */
    private void upsertLinesFromAssignments(PayrollWeek week, LocalDate start, LocalDate end) {
        var assignments = assignmentRepo.findByDateBetween(start, end);

        // Aggregate per member (null-safe, like finalizeWeek)
        Map<Long, MemberAgg> agg = new HashMap<>();
        for (var a : assignments) {
            var tm = a.getTeamMember();
            if (tm == null) continue;
            long mid = tm.getId();
            agg.computeIfAbsent(mid, k -> new MemberAgg(tm.getName())).accept(a);
        }

        // Index existing lines to preserve overrides/adjustments
        Map<Long, PayrollLine> byMember = new HashMap<>();
        if (week.getLines() != null) {
            for (var l : week.getLines()) byMember.put(l.getTeamMemberId(), l);
        }

        // Upsert from aggregation
        Set<Long> touched = new HashSet<>();
        for (var e : agg.entrySet()) {
            long mid = e.getKey();
            var m = e.getValue();
            double effOtPayFromDays = m.otPayFromDailyRates(); // sets m.totalBase & m.totalOtHours

            var line = byMember.get(mid);
            if (line == null) {
                line = PayrollLine.builder()
                        .teamMemberId(mid)
                        .teamMemberName(m.getName())
                        .build();
                week.addLine(line); // sets back-ref; relies on cascade on PayrollWeek.lines
                byMember.put(mid, line);
            } else if (line.getTeamMemberName() == null || line.getTeamMemberName().isBlank()) {
                line.setTeamMemberName(m.getName());
            }

            line.setBaseWages(m.getTotalBase());
            line.setComputedOtHours(m.getTotalOtHours());
            line.setComputedOtPay(effOtPayFromDays);
            touched.add(mid);
        }

        // Zero computed parts for members not touched this week (keep overrides/adjustments)
        if (week.getLines() != null) {
            for (var l : week.getLines()) {
                if (!touched.contains(l.getTeamMemberId())) {
                    l.setBaseWages(0d);
                    l.setComputedOtHours(0d);
                    l.setComputedOtPay(0d);
                }
            }
        }

        // Recompute effective OT with canonical hourly fallback (same rule as finalizeWeek)
        var hourlyByMid = canonicalHourlyByMember(start, end);
        if (week.getLines() != null) {
            for (var l : week.getLines()) {
                if (num(l.getComputedOtHours()) > 0 && num(l.getComputedOtPay()) == 0) {
                    double hourly = hourlyByMid.getOrDefault(l.getTeamMemberId(), 0.0);
                    l.setComputedOtPay(hourly * l.getComputedOtHours());
                }
                recalcLineWithHourly(l, hourlyByMid);
            }
        }
    }

    @Transactional
    public PayrollWeekDTO addAdjustment(Long weekId, Long lineId, double amount, String note) {
        if (amount == 0.0) throw new BadRequestException("Amount must be non-zero");

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

        // link child
        var adj = PayrollAdjustment.builder()
                .amount(amount)
                .note(note == null ? "" : note.trim())
                .build();
        line.addAdjustment(adj);
        adjRepo.saveAndFlush(adj); // ensure child id

        // ⬇️ Recompute using canonical hourly fallback
        var hourlyByMid = canonicalHourlyByMember(week.getWeekStart(), week.getWeekEnd());
        recalcLineWithHourly(line, hourlyByMid);
        recalcWeek(week);

        week.setUpdatedAt(OffsetDateTime.now());
        return mapper.toDTO(weekRepo.saveAndFlush(week));
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

        // ⬇️ Recompute using canonical hourly fallback
        var hourlyByMid = canonicalHourlyByMember(week.getWeekStart(), week.getWeekEnd());
        recalcLineWithHourly(line, hourlyByMid);
        recalcWeek(week);

        week.setUpdatedAt(OffsetDateTime.now());
        return mapper.toDTO(weekRepo.saveAndFlush(week));
    }

    @Transactional
    public PayrollWeekDTO finalizeWeek(Long weekId) {
        var week = weekRepo.findById(weekId).orElseThrow();

        LocalDate start = week.getWeekStart();
        LocalDate end   = week.getWeekEnd();

        // 1) Aggregate CURRENT assignments Sat→Thu
        var assignments = assignmentRepo.findByDateBetween(start, end); // inclusive
        Map<Long, MemberAgg> agg = new HashMap<>();
        for (var a : assignments) {
            var tm = a.getTeamMember();
            if (tm == null) continue;
            long mid = tm.getId();
            agg.computeIfAbsent(mid, k -> new MemberAgg(tm.getName())).accept(a);
        }

        // 2) Index existing lines (to keep overrides & adjustments)
        Map<Long, PayrollLine> byMember = new HashMap<>();
        if (week.getLines() != null) {
            for (var l : week.getLines()) byMember.put(l.getTeamMemberId(), l);
        }

        // 3) Upsert lines from aggregation
        Set<Long> touched = new HashSet<>();
        for (var e : agg.entrySet()) {
            long mid = e.getKey();
            var m = e.getValue();
            double effOtPayFromDays = m.otPayFromDailyRates(); // sets totalBase & totalOtHours

            var line = byMember.get(mid);
            if (line == null) {
                line = PayrollLine.builder()
                        .teamMemberId(mid)
                        .teamMemberName(m.getName())
                        .build();
                week.addLine(line); // set back-ref
                byMember.put(mid, line);
            } else if (line.getTeamMemberName() == null || line.getTeamMemberName().isBlank()) {
                line.setTeamMemberName(m.getName());
            }

            // refresh computed fields from CURRENT assignments
            line.setBaseWages(m.getTotalBase());
            line.setComputedOtHours(m.getTotalOtHours());
            line.setComputedOtPay(effOtPayFromDays);

            touched.add(mid);
        }

        // 4) Lines with no assignments this week → zero computed parts, keep adjustments/override
        for (var l : week.getLines()) {
            if (!touched.contains(l.getTeamMemberId())) {
                l.setBaseWages(0d);
                l.setComputedOtHours(0d);
                l.setComputedOtPay(0d);
            }
        }

        // 5) Recompute lines with canonical hourly fallback so effective OT is never dropped
        var hourlyByMid = canonicalHourlyByMember(start, end);
        for (var l : week.getLines()) {
            // If we have hours but computed pay is zero, backfill computed pay from hourly
            if (num(l.getComputedOtHours()) > 0 && num(l.getComputedOtPay()) == 0) {
                double hourly = hourlyByMid.getOrDefault(l.getTeamMemberId(), 0.0);
                l.setComputedOtPay(hourly * l.getComputedOtHours());
            }
            recalcLineWithHourly(l, hourlyByMid);
        }

        // 6) Roll up week snapshot and freeze
        recalcWeek(week);
        week.setStatus(PayrollWeek.Status.FINALIZED);
        week.setUpdatedAt(OffsetDateTime.now());

        return mapper.toDTO(weekRepo.saveAndFlush(week));
    }

    /** Original recalc, kept for reference. */
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

    /** Recalc using a canonical hourly fallback when computed hours/pay are missing. */
    private void recalcLineWithHourly(PayrollLine l, Map<Long, Double> hourlyByMid) {
        double computedOtHours = num(l.getComputedOtHours());
        double hourly;

        if (computedOtHours > 0) {
            double cop = num(l.getComputedOtPay());
            hourly = (cop > 0) ? (cop / computedOtHours)
                    : hourlyByMid.getOrDefault(l.getTeamMemberId(), 0.0);
        } else {
            hourly = hourlyByMid.getOrDefault(l.getTeamMemberId(), 0.0);
        }

        double effectiveHours = (l.getOtHoursOverride() != null) ? l.getOtHoursOverride() : computedOtHours;
        l.setEffectiveOtPay(hourly * effectiveHours);

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
            tOt    += num(l.getEffectiveOtPay());  // effective OT (respects override)
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

    /**
     * Build canonical hourly per member from week assignments.
     * If member has OT hours in the week, use (sum(daily/8*ot)/sum(ot)).
     * Otherwise fall back to avg daily wage over worked days divided by 8.
     */
    private Map<Long, Double> canonicalHourlyByMember(LocalDate start, LocalDate end) {
        var assignments = assignmentRepo.findByDateBetween(start, end);
        Map<Long, MemberAgg> agg = new HashMap<>();
        for (var a : assignments) {
            var tm = a.getTeamMember();
            if (tm == null) continue;
            long mid = tm.getId();
            agg.computeIfAbsent(mid, k -> new MemberAgg(tm.getName())).accept(a);
        }

        Map<Long, Double> out = new HashMap<>();
        for (var e : agg.entrySet()) {
            var m = e.getValue();
            double otPay = m.otPayFromDailyRates(); // fills totalBase & totalOtHours
            long workedDays = m.days.values().stream().filter(d -> d.getDailyWage() > 0).count();
            double hourly =
                    (m.totalOtHours > 0)
                            ? (otPay / m.totalOtHours)
                            : (workedDays > 0 ? (m.totalBase / workedDays) / 8.0 : 0.0);
            out.put(e.getKey(), hourly);
        }
        return out;
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

/*
**fix(payroll): upsert lines on week load so draft weeks return real line IDs**

        * Change `generateOrLoad(...)` to **load-or-create** the week and, when `DRAFT`,
        **upsert payroll lines from current Sat→Thu assignments**.
        * New helper `upsertLinesFromAssignments(...)`:

        * aggregates per-member, creates/matches `PayrollLine`s,
        * preserves adjustments and OT overrides,
        * zeros computed parts for untouched members,
  * recomputes effective OT with canonical-hourly fallback,
  * calls `recalcWeek(...)` and updates `updatedAt`.
        * Return the saved week DTO with populated `lines`.

        **Why:** existing `generateOrLoad` returned an empty `lines` array when the week
existed but had been created before assignments. The UI then produced `tmp-*`
rows with no backend `line.id`, keeping **Apply** / **Add adjustment** disabled.

No API changes; `finalizeWeek(...)` unchanged.

        Follow-ups suggested: add `UNIQUE (payroll_week_id, team_member_id)` and keep
entity cascade/back-ref as configured.
*/