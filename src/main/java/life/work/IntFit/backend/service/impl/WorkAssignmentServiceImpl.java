package life.work.IntFit.backend.service.impl;

import life.work.IntFit.backend.dto.daily_assignments.AllocationErrorDTO;
import life.work.IntFit.backend.dto.daily_assignments.AssignmentHistoryDTO;
import life.work.IntFit.backend.dto.daily_assignments.DailyAssignmentsViewDTO;
import life.work.IntFit.backend.dto.daily_assignments.DailyOvertimeDTO;
import life.work.IntFit.backend.dto.daily_assignments.DailyWorkAssignmentDTO;
import life.work.IntFit.backend.exception.error.AllocationValidationException;
import life.work.IntFit.backend.dto.daily_assignments.OvertimeDTO;
import life.work.IntFit.backend.dto.daily_assignments.SimpleAssignmentDTO;
import life.work.IntFit.backend.dto.daily_assignments.WorkAssignmentDTO;
import life.work.IntFit.backend.mapper.WorkAssignmentMapper;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.model.entity.TeamMember;
import life.work.IntFit.backend.model.entity.WorkAssignment;
import life.work.IntFit.backend.model.entity.WorkAssignmentOvertime;
import life.work.IntFit.backend.repository.MasterWorksiteRepository;
import life.work.IntFit.backend.repository.TeamMemberRepository;
import life.work.IntFit.backend.repository.WorkAssignmentOvertimeRepository;
import life.work.IntFit.backend.repository.WorkAssignmentRepository;
import life.work.IntFit.backend.repository.projection.SiteCountView;
import life.work.IntFit.backend.service.WorkAssignmentService;
import life.work.IntFit.backend.service.allocation.AllocationPlanner;
import life.work.IntFit.backend.service.allocation.DecimalHours;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkAssignmentServiceImpl implements WorkAssignmentService {

    private final WorkAssignmentRepository workAssignmentRepo;
    private final MasterWorksiteRepository masterWorksiteRepo;
    private final TeamMemberRepository teamMemberRepo;
    private final WorkAssignmentOvertimeRepository overtimeRepo;
    private final WorkAssignmentMapper mapper;
    private final life.work.IntFit.backend.service.AssignmentConcurrency concurrency;

    @Override
    @Transactional(readOnly = true)
    public List<WorkAssignmentDTO> getAssignmentsByDate(LocalDate date) {
        return toRows(workAssignmentRepo.findByDate(date), overtimeRepo.findAllByDate(date));
    }

    @Override
    @Transactional(readOnly = true, isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public DailyAssignmentsViewDTO getDay(LocalDate date) {
        long revision = concurrency.revision(date);
        List<WorkAssignment> rows = workAssignmentRepo.findByDate(date);
        List<WorkAssignmentOvertime> overtimeRows = overtimeRepo.findAllByDate(date);

        Set<Long> assigned = rows.stream().map(r -> r.getTeamMember().getId()).collect(Collectors.toSet());
        List<DailyOvertimeDTO> overtime = overtimeRows.stream()
                .sorted(Comparator.comparing(o -> o.getTeamMember().getId()))
                .map(o -> {
                    Double hours = o.getOvertimeHours();
                    return new DailyOvertimeDTO(
                            o.getTeamMember().getId(),
                            o.getTeamMember().getName(),
                            hours,
                            hours == null || !Double.isFinite(hours)
                                    ? null
                                    : DecimalHours.toPlainString(DecimalHours.canonical(hours)),
                            assigned.contains(o.getTeamMember().getId()));
                })
                .toList();
        return new DailyAssignmentsViewDTO(date, toRows(rows, overtimeRows), overtime, revision);
    }

    /**
     * Sparse daily distinct-worker counts for the range, plus the history floor.
     *
     * siteCountsByRange groups by (date, member), so it already emits exactly
     * one row per worker per date regardless of how many worksites that worker
     * was on. Counting those rows per date therefore gives distinct workers
     * without a second query, and overtime-only records are excluded because
     * they live in a different table.
     *
     * Dates with no assignment are simply absent; the caller fills the calendar.
     */
    @Override
    @Transactional(readOnly = true)
    public AssignmentHistoryDTO getHistory(LocalDate from, LocalDate to) {
        Map<LocalDate, Integer> workersByDate = new TreeMap<>();
        for (SiteCountView row : workAssignmentRepo.siteCountsByRange(from, to)) {
            workersByDate.merge(row.getDate(), 1, Integer::sum);
        }

        List<AssignmentHistoryDTO.DayCount> days = workersByDate.entrySet().stream()
                .map(e -> new AssignmentHistoryDTO.DayCount(e.getKey(), e.getValue()))
                .toList();

        return new AssignmentHistoryDTO(
                from, to, workAssignmentRepo.findEarliestAssignmentDate(), days);
    }

    private List<WorkAssignmentDTO> toRows(List<WorkAssignment> rows, List<WorkAssignmentOvertime> overtimeRows) {
        // site counts per member for that date
        Map<Long, Integer> siteCounts = rows.stream()
                .collect(Collectors.groupingBy(
                        ra -> ra.getTeamMember().getId(),
                        Collectors.reducing(0, ra -> 1, Integer::sum)
                ));

        // overtime per member for that date (Double)
        Map<Long, Double> overtimeByMember = overtimeRows.stream()
                .collect(Collectors.toMap(
                        o -> o.getTeamMember().getId(),
                        WorkAssignmentOvertime::getOvertimeHours
                ));

        // map rows with enriched data (overtimeHours + allocatedHours)
        List<WorkAssignmentDTO> dtos = new ArrayList<>(rows.size());
        for (WorkAssignment wa : rows) {
            Long memberId = wa.getTeamMember().getId();
            Double ot = overtimeByMember.get(memberId);                // may be null
            Integer count = siteCounts.getOrDefault(memberId, 0);      // > 0 in practice
            dtos.add(mapper.toDTO(wa, ot, count));
        }
        return dtos;
    }

    /**
     * Replaces the day's assignments, overtime and allocations in one transaction.
     *
     * <p>Everything that can be rejected (unknown ids, allocation rules) is
     * checked before the first write. Writes then run in this single
     * transaction; the repository methods used here either have no
     * transaction of their own or use the default REQUIRED propagation, so a
     * failure at any step rolls back the assignment replacement, overtime
     * changes and allocation values together.
     */
    @Override
    @Transactional(rollbackFor = Exception.class, isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void saveAssignmentsForDate(DailyWorkAssignmentDTO dto) {
        final LocalDate date = Objects.requireNonNull(dto.getDate(), "date is required");
        concurrency.requireRevision(dto.getExpectedRevision(), concurrency.lockDay(date));
        concurrency.editable(date);
        if (dto.isAllocationsRepeated()) {
            throw new AllocationValidationException(List.of(new AllocationErrorDTO(
                    null, null, null, "DUPLICATE_FIELD",
                    "\"allocations\" appears more than once in the request; send it once.")));
        }

        // --- 1) Resolve the submitted assignments (dedup by member+site) without writing
        List<SimpleAssignmentDTO> incoming = (dto.getAssignments() == null)
                ? Collections.emptyList()
                : dto.getAssignments();

        Set<String> uniqPairs = new HashSet<>(incoming.size() * 2);
        List<WorkAssignment> toSave = new ArrayList<>();
        Map<Long, Set<Long>> newSitesByMember = new HashMap<>();
        Map<Long, String> memberNames = new HashMap<>();
        Map<Long, String> worksiteNames = new HashMap<>();

        for (SimpleAssignmentDTO a : incoming) {
            if (a == null || a.getTeamMemberId() == null || a.getMasterWorksiteId() == null) continue;

            long tmId = a.getTeamMemberId();
            long msId = a.getMasterWorksiteId();
            String key = tmId + ":" + msId;
            if (!uniqPairs.add(key)) continue; // skip duplicate pairs in payload

            TeamMember member = teamMemberRepo.findById(tmId)
                    .orElseThrow(() -> new IllegalArgumentException("TeamMember not found: " + tmId));
            MasterWorksite master = masterWorksiteRepo.findById(msId)
                    .orElseThrow(() -> new IllegalArgumentException("MasterWorksite not found: " + msId));

            toSave.add(WorkAssignment.builder()
                    .teamMember(member)
                    .masterWorksite(master)
                    .date(date)
                    .build());
            newSitesByMember.computeIfAbsent(tmId, k -> new HashSet<>()).add(msId);
            memberNames.put(tmId, member.getName());
            worksiteNames.put(msId, master.getApprovedName());
        }

        // --- 2) Resolve submitted overtime: last-write-wins per member, negatives clamped to 0
        List<OvertimeDTO> overtimeList = (dto.getOvertime() == null)
                ? Collections.emptyList()
                : dto.getOvertime();

        Map<Long, BigDecimal> lastPerMember = new LinkedHashMap<>();
        for (OvertimeDTO ot : overtimeList) {
            if (ot == null || ot.getTeamMemberId() == null) continue;
            lastPerMember.put(ot.getTeamMemberId(), ot.getOvertimeHours());
        }
        // Positivity is decided on the stored double, exactly as before; the exact text is kept for allocation checks.
        Map<Long, Double> storedHours = new LinkedHashMap<>();
        Map<Long, BigDecimal> positiveOvertime = new HashMap<>();
        lastPerMember.forEach((mid, exact) -> {
            double hours = exact == null ? 0d : DecimalHours.toDouble(exact);
            if (hours < 0d) hours = 0d;
            storedHours.put(mid, hours);
            if (hours > 0d) positiveOvertime.put(mid, exact);
        });

        // --- 3) Merge allocations with what is stored and validate the result (throws before any write)
        List<WorkAssignment> existingRows = workAssignmentRepo.findByDate(date);
        for (WorkAssignment row : existingRows) {
            memberNames.putIfAbsent(row.getTeamMember().getId(), row.getTeamMember().getName());
            worksiteNames.putIfAbsent(row.getMasterWorksite().getId(), row.getMasterWorksite().getApprovedName());
        }
        AllocationPlanner.Plan plan = AllocationPlanner.plan(new AllocationPlanner.Input(
                newSitesByMember,
                positiveOvertime,
                existingRows,
                dto.getAllocations(),
                memberNames,
                worksiteNames));

        // --- 4) Replace assignments for the date, carrying the planned allocation values
        workAssignmentRepo.deleteByDate(date);
        for (WorkAssignment row : toSave) {
            Long memberId = row.getTeamMember().getId();
            Long worksiteId = row.getMasterWorksite().getId();
            row.setRegularHoursOverride(plan.regularFor(memberId, worksiteId));
            row.setOvertimeHoursOverride(plan.overtimeFor(memberId, worksiteId));
        }
        if (!toSave.isEmpty()) {
            workAssignmentRepo.saveAll(toSave);
        }

        concurrency.bump(date);

        // --- 5) OVERTIME: idempotent (NO deleteAllByDate); unchanged semantics
        // Delete rows that exist for this date but are now absent (or zeroed) in the payload
        for (WorkAssignmentOvertime existing : overtimeRepo.findAllByDate(date)) {
            Long mid = existing.getTeamMember().getId();
            if (!positiveOvertime.containsKey(mid)) {
                overtimeRepo.deleteByMemberAndDate(mid, date);
            }
        }

        // Upsert positive hours; delete explicit zeros
        for (Map.Entry<Long, Double> e : storedHours.entrySet()) {
            Long mid = e.getKey();
            Double hours = e.getValue();
            if (hours <= 0d) {
                overtimeRepo.deleteByMemberAndDate(mid, date);
            } else {
                // relies on UNIQUE(team_member_id, date); uses ON DUPLICATE KEY UPDATE
                overtimeRepo.upsert(mid, date, hours);
            }
        }
    }


}
