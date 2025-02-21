package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.TeamMemberDTO;
import life.work.IntFit.backend.service.TeamMemberService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/team")
@CrossOrigin("*")
public class TeamMemberController {
    private final TeamMemberService teamMemberService;

    public TeamMemberController(TeamMemberService teamMemberService) {
        this.teamMemberService = teamMemberService;
    }

    @GetMapping
    public List<TeamMemberDTO> getAllTeamMembers() {
        return teamMemberService.getAllTeamMembers();
    }

    @GetMapping("/{id}")
    public Optional<TeamMemberDTO> getTeamMemberById(@PathVariable Long id) {
        return teamMemberService.getTeamMemberById(id);
    }

    @PostMapping
    public TeamMemberDTO addTeamMember(@RequestBody TeamMemberDTO teamMemberDTO) {
        return teamMemberService.addTeamMember(teamMemberDTO);
    }

    @PutMapping("/{id}")
    public TeamMemberDTO updateTeamMember(@PathVariable Long id, @RequestBody TeamMemberDTO updatedDTO) {
        return teamMemberService.updateTeamMember(id, updatedDTO);
    }

    @DeleteMapping("/{id}")
    public void deleteTeamMember(@PathVariable Long id) {
        teamMemberService.deleteTeamMember(id);
    }
}
