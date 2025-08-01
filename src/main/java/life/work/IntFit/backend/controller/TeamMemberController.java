package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.TeamMemberDTO;
import life.work.IntFit.backend.service.TeamMemberService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/team")
@CrossOrigin("*")
public class TeamMemberController {
    private final TeamMemberService teamMemberService;

    public TeamMemberController(TeamMemberService teamMemberService) {
        this.teamMemberService = teamMemberService;
    }

    @GetMapping
    public ResponseEntity<List<TeamMemberDTO>> getAllTeamMembers() {
        return ResponseEntity.ok(teamMemberService.getAllTeamMembers());
    }

    @GetMapping("/{id}")
    public ResponseEntity<TeamMemberDTO> getTeamMemberById(@PathVariable Long id) {
        return teamMemberService.getTeamMemberById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<TeamMemberDTO> addTeamMember(@RequestBody TeamMemberDTO teamMemberDTO) {
        if (teamMemberDTO == null || teamMemberDTO.getName() == null || teamMemberDTO.getName().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        return ResponseEntity.ok(teamMemberService.addTeamMember(teamMemberDTO));
    }

    @PutMapping("/{id}")
    public ResponseEntity<TeamMemberDTO> updateTeamMember(@PathVariable Long id, @RequestBody TeamMemberDTO updatedDTO) {
        return ResponseEntity.ok(teamMemberService.updateTeamMember(id, updatedDTO));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTeamMember(@PathVariable Long id) {
        if (teamMemberService.getTeamMemberById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        teamMemberService.deleteTeamMember(id);
        return ResponseEntity.noContent().build();
    }
}
