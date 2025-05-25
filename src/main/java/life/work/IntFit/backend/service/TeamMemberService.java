package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.TeamMemberDTO;
import life.work.IntFit.backend.mapper.TeamMemberMapper;
import life.work.IntFit.backend.model.entity.TeamMember;
import life.work.IntFit.backend.repository.TeamMemberRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class TeamMemberService {
    private final TeamMemberRepository teamMemberRepository;
    private final TeamMemberMapper teamMemberMapper;

    public TeamMemberService(TeamMemberRepository teamMemberRepository, TeamMemberMapper teamMemberMapper) {
        this.teamMemberRepository = teamMemberRepository;
        this.teamMemberMapper = teamMemberMapper;
    }

    public List<TeamMemberDTO> getAllTeamMembers() {
        List<TeamMember> teamMembers = teamMemberRepository.findAll();
        return teamMemberMapper.toDTOList(teamMembers);
    }

    public Optional<TeamMemberDTO> getTeamMemberById(Long id) {
        return teamMemberRepository.findById(id)
                .map(teamMemberMapper::toDTO);
    }

    public TeamMemberDTO addTeamMember(TeamMemberDTO teamMemberDTO) {
        TeamMember teamMember = teamMemberMapper.toEntity(teamMemberDTO);
        TeamMember savedTeamMember = teamMemberRepository.save(teamMember);
        return teamMemberMapper.toDTO(savedTeamMember);
    }

    public TeamMemberDTO updateTeamMember(Long id, TeamMemberDTO updatedDTO) {
        return teamMemberRepository.findById(id)
                .map(existingMember -> {
                    existingMember.setName(updatedDTO.getName());
                    existingMember.setRole(updatedDTO.getRole());
                    existingMember.setExperience(updatedDTO.getExperience());
                    existingMember.setContact(updatedDTO.getContact());
                    existingMember.setDailyWage(updatedDTO.getDailyWage()); // Add this line
                    TeamMember updatedMember = teamMemberRepository.save(existingMember);
                    return teamMemberMapper.toDTO(updatedMember);
                }).orElseThrow(() -> new RuntimeException("Team member not found"));
    }

    public void deleteTeamMember(Long id) {
        teamMemberRepository.deleteById(id);
    }
}
