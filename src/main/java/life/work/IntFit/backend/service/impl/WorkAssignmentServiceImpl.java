package life.work.IntFit.backend.service.impl;

import life.work.IntFit.backend.dto.daily_assignments.DailyWorkAssignmentDTO;
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
import life.work.IntFit.backend.service.WorkAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

    @Override
    @Transactional(readOnly = true)
    public List<WorkAssignmentDTO> getAssignmentsByDate(LocalDate date) {
        // Load all assignments for that date
        List<WorkAssignment> rows = workAssignmentRepo.findByDate(date);

        // site counts per member for that date
        Map<Long, Integer> siteCounts = rows.stream()
                .collect(Collectors.groupingBy(
                        ra -> ra.getTeamMember().getId(),
                        Collectors.reducing(0, ra -> 1, Integer::sum)
                ));

        // overtime per member for that date
        Map<Long, Integer> overtimeByMember = overtimeRepo.findAllByDate(date).stream()
                .collect(Collectors.toMap(
                        o -> o.getTeamMember().getId(),
                        WorkAssignmentOvertime::getOvertimeHours
                ));

        // map rows with enriched data (overtimeHours + allocatedHours)
        List<WorkAssignmentDTO> dtos = new ArrayList<>(rows.size());
        for (WorkAssignment wa : rows) {
            Long memberId = wa.getTeamMember().getId();
            Integer ot = overtimeByMember.get(memberId);                 // may be null
            Integer count = siteCounts.getOrDefault(memberId, 0);        // > 0 in practice
            dtos.add(mapper.toDTO(wa, ot, count));
        }
        return dtos;
    }

    @Override
    @Transactional
    public void saveAssignmentsForDate(DailyWorkAssignmentDTO dto) {
        final LocalDate date = Objects.requireNonNull(dto.getDate(), "date is required");

        // --- 1) Replace assignments for the date (delete then insert, dedup by member+site)
        workAssignmentRepo.deleteByDate(date);

        List<SimpleAssignmentDTO> incoming = (dto.getAssignments() == null)
                ? Collections.emptyList()
                : dto.getAssignments();

        Set<String> uniqPairs = new HashSet<>(incoming.size() * 2);
        List<WorkAssignment> toSave = new ArrayList<>();

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
        }

        if (!toSave.isEmpty()) {
            workAssignmentRepo.saveAll(toSave);
        }

        // --- 2) Replace overtime rows for the date (delete then insert, dedup by member)
        overtimeRepo.deleteAllByDate(date);

        List<OvertimeDTO> overtimeList = (dto.getOvertime() == null)
                ? Collections.emptyList()
                : dto.getOvertime();

        // last-write-wins per memberId
        Map<Long, Integer> lastPerMember = new LinkedHashMap<>();
        for (OvertimeDTO ot : overtimeList) {
            if (ot == null || ot.getTeamMemberId() == null) continue;
            long tmId = ot.getTeamMemberId();
            int hours = Math.max(0, Optional.ofNullable(ot.getOvertimeHours()).orElse(0));
            lastPerMember.put(tmId, hours);
        }

        List<WorkAssignmentOvertime> otEntities = new ArrayList<>(lastPerMember.size());
        for (Map.Entry<Long, Integer> e : lastPerMember.entrySet()) {
            long tmId = e.getKey();
            int hours = e.getValue();

            if (hours <= 0) {
                // 0 means no OT row for that member/date (we already deleted all for the day)
                continue;
            }

            TeamMember member = teamMemberRepo.findById(tmId)
                    .orElseThrow(() -> new IllegalArgumentException("TeamMember not found: " + tmId));

            otEntities.add(WorkAssignmentOvertime.builder()
                    .teamMember(member)
                    .date(date)
                    .overtimeHours(hours)
                    .build());
        }

        if (!otEntities.isEmpty()) {
            overtimeRepo.saveAll(otEntities);
        }
    }

}
