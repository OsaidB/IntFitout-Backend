package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.TeamMemberDTO;
import life.work.IntFit.backend.mapper.TeamMemberMapper;
import life.work.IntFit.backend.model.entity.TeamMember;
import life.work.IntFit.backend.model.entity.TeamMemberWageChange;
import life.work.IntFit.backend.repository.TeamMemberRepository;
import life.work.IntFit.backend.repository.TeamMemberWageChangeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Service
public class TeamMemberService {
    private final TeamMemberRepository teamMemberRepository;
    private final TeamMemberMapper teamMemberMapper;
    private final TeamMemberWageChangeRepository wageChangeRepository;

    private static final ZoneId HEB = ZoneId.of("Asia/Hebron");

    public TeamMemberService(TeamMemberRepository teamMemberRepository,
                             TeamMemberMapper teamMemberMapper,
                             TeamMemberWageChangeRepository wageChangeRepository) {
        this.teamMemberRepository = teamMemberRepository;
        this.teamMemberMapper = teamMemberMapper;
        this.wageChangeRepository = wageChangeRepository;
    }

    public List<TeamMemberDTO> getAllTeamMembers() {
        List<TeamMember> teamMembers = teamMemberRepository.findAll();
        return teamMemberMapper.toDTOList(teamMembers);
    }

    public Optional<TeamMemberDTO> getTeamMemberById(Long id) {
        return teamMemberRepository.findById(id).map(teamMemberMapper::toDTO);
    }

    @Transactional
    public TeamMemberDTO addTeamMember(TeamMemberDTO teamMemberDTO) {
        TeamMember teamMember = teamMemberMapper.toEntity(teamMemberDTO);
        TeamMember saved = teamMemberRepository.save(teamMember);

        // Audit initial wage if present
        if (saved.getDailyWage() != null) {
            TeamMemberWageChange change = TeamMemberWageChange.builder()
                    .teamMember(saved)
                    .oldWage(null)
                    .newWage(saved.getDailyWage())
                    .changedAt(LocalDateTime.now(HEB))
                    .note("Initial wage on creation")
                    .build();
            wageChangeRepository.save(change);
        }

        return teamMemberMapper.toDTO(saved);
    }

    @Transactional
    public TeamMemberDTO updateTeamMember(Long id, TeamMemberDTO updatedDTO) {
        return teamMemberRepository.findById(id)
                .map(existing -> {
                    Double oldWage = existing.getDailyWage();
                    boolean hasNewWage = updatedDTO.getDailyWage() != null;
                    boolean wageChanged = hasNewWage && !Objects.equals(oldWage, updatedDTO.getDailyWage());

                    // Partial update — only set fields that are provided
                    if (updatedDTO.getName() != null) existing.setName(updatedDTO.getName());
                    if (updatedDTO.getRole() != null) existing.setRole(updatedDTO.getRole());
                    if (updatedDTO.getExperience() != null) existing.setExperience(updatedDTO.getExperience());
                    if (updatedDTO.getContact() != null) existing.setContact(updatedDTO.getContact()); // contact = phone
                    if (hasNewWage) existing.setDailyWage(updatedDTO.getDailyWage());

                    TeamMember saved = teamMemberRepository.save(existing);

                    if (wageChanged) {
                        TeamMemberWageChange change = TeamMemberWageChange.builder()
                                .teamMember(saved)
                                .oldWage(oldWage)
                                .newWage(updatedDTO.getDailyWage())
                                .changedAt(LocalDateTime.now(HEB))
                                .note(null) // add a note in DTO & pass it if you want
                                .build();
                        wageChangeRepository.save(change);
                    }

                    return teamMemberMapper.toDTO(saved);
                })
                .orElseThrow(() -> new RuntimeException("Team member not found"));
    }

    @Transactional
    public void deleteTeamMember(Long id) {
        teamMemberRepository.deleteById(id);
    }
}
