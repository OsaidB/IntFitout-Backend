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
import life.work.IntFit.backend.repository.WorkAssignmentOvertimeRepository;
import life.work.IntFit.backend.repository.WorkAssignmentRepository;
import life.work.IntFit.backend.service.WorkAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkAssignmentServiceImpl implements WorkAssignmentService {

    private final WorkAssignmentRepository workAssignmentRepo;
    private final WorkAssignmentOvertimeRepository overtimeRepo;
    private final WorkAssignmentMapper mapper;

    @PersistenceContext
    private EntityManager em;

    @Override
    @Transactional(readOnly = true)
    public List<WorkAssignmentDTO> getAssignmentsByDate(LocalDate date) {
        // ✅ Fetch-join to avoid N+1 on mapping
        List<WorkAssignment> rows = workAssignmentRepo.findByDateWithJoins(date);

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

        // --- 1) Replace assignments for the date (delete then insert)
        workAssignmentRepo.deleteByDate(date);

        // Optional: deduplicate pairs in case UI sends duplicates
        Set<String> seen = new HashSet<>();
        List<SimpleAssignmentDTO> incoming = dto.getAssignments() == null
                ? List.of()
                : dto.getAssignments().stream()
                .filter(a -> a != null && a.getTeamMemberId() != null && a.getMasterWorksiteId() != null)
                .filter(a -> seen.add(a.getTeamMemberId() + ":" + a.getMasterWorksiteId()))
                .toList();

        List<WorkAssignment> toSave = new ArrayList<>(incoming.size());
        for (SimpleAssignmentDTO a : incoming) {
            // ✅ No SELECT here; attach proxies by id
            TeamMember memberRef = em.getReference(TeamMember.class, a.getTeamMemberId());
            MasterWorksite siteRef = em.getReference(MasterWorksite.class, a.getMasterWorksiteId());

            toSave.add(WorkAssignment.builder()
                    .teamMember(memberRef)
                    .masterWorksite(siteRef)
                    .date(date)
                    .build());
        }

        if (!toSave.isEmpty()) {
            workAssignmentRepo.saveAll(toSave);
        }

        // --- 2) Replace overtime rows for the date (delete then insert)
        overtimeRepo.deleteAllByDate(date);

        List<OvertimeDTO> overtimeList = dto.getOvertime();
        if (overtimeList != null && !overtimeList.isEmpty()) {
            List<WorkAssignmentOvertime> otEntities = new ArrayList<>(overtimeList.size());
            for (OvertimeDTO ot : overtimeList) {
                if (ot == null || ot.getTeamMemberId() == null) continue;
                int hours = Math.max(0, ot.getOvertimeHours());

                // ✅ No SELECT here either
                TeamMember memberRef = em.getReference(TeamMember.class, ot.getTeamMemberId());

                otEntities.add(
                        WorkAssignmentOvertime.builder()
                                .teamMember(memberRef)
                                .date(date)
                                .overtimeHours(hours)
                                .build()
                );
            }
            if (!otEntities.isEmpty()) {
                overtimeRepo.saveAll(otEntities);
            }
        }
    }
}
