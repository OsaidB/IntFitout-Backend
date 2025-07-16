package life.work.IntFit.backend.service.impl;

import life.work.IntFit.backend.dto.DailyWorkAssignmentDTO;
import life.work.IntFit.backend.dto.SimpleAssignmentDTO;
import life.work.IntFit.backend.dto.WorkAssignmentDTO;
import life.work.IntFit.backend.mapper.WorkAssignmentMapper;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.model.entity.TeamMember;
import life.work.IntFit.backend.model.entity.WorkAssignment;
import life.work.IntFit.backend.repository.MasterWorksiteRepository;
import life.work.IntFit.backend.repository.TeamMemberRepository;
import life.work.IntFit.backend.repository.WorkAssignmentRepository;
import life.work.IntFit.backend.service.WorkAssignmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WorkAssignmentServiceImpl implements WorkAssignmentService {

    private final WorkAssignmentRepository workAssignmentRepo;
    private final MasterWorksiteRepository masterWorksiteRepo;
    private final TeamMemberRepository teamMemberRepo;
    private final WorkAssignmentMapper mapper;

    @Override
    public List<WorkAssignmentDTO> getAssignmentsByDate(LocalDate date) {
        return workAssignmentRepo.findByDate(date).stream()
                .map(mapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void saveAssignmentsForDate(DailyWorkAssignmentDTO dto) {
        List<WorkAssignment> toSave = dto.getAssignments().stream().map(a -> {
            TeamMember member = teamMemberRepo.findById(a.getTeamMemberId())
                    .orElseThrow(() -> new IllegalArgumentException("TeamMember not found: " + a.getTeamMemberId()));
            MasterWorksite master = masterWorksiteRepo.findById(a.getMasterWorksiteId())
                    .orElseThrow(() -> new IllegalArgumentException("MasterWorksite not found: " + a.getMasterWorksiteId()));
            return WorkAssignment.builder()
                    .teamMember(member)
                    .masterWorksite(master)
                    .date(dto.getDate())
                    .build();
        }).collect(Collectors.toList());

        workAssignmentRepo.saveAll(toSave);
    }
}