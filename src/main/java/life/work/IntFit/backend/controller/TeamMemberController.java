package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.TeamMemberDTO;
import life.work.IntFit.backend.dto.TeamMemberWageChangeDTO;
import life.work.IntFit.backend.mapper.TeamMemberWageChangeMapper;
import life.work.IntFit.backend.service.TeamMemberService;
import life.work.IntFit.backend.repository.TeamMemberWageChangeRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/team")
@CrossOrigin("*")
public class TeamMemberController {

    private final TeamMemberService teamMemberService;
    private final TeamMemberWageChangeRepository wageChangeRepository;
    private final TeamMemberWageChangeMapper wageChangeMapper;

    public TeamMemberController(TeamMemberService teamMemberService,
                                TeamMemberWageChangeRepository wageChangeRepository,
                                TeamMemberWageChangeMapper wageChangeMapper) {
        this.teamMemberService = teamMemberService;
        this.wageChangeRepository = wageChangeRepository;
        this.wageChangeMapper = wageChangeMapper;
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

    // Full update (your service method already handles partial fields safely)
    @PutMapping("/{id}")
    public ResponseEntity<TeamMemberDTO> updateTeamMember(@PathVariable Long id,
                                                          @RequestBody TeamMemberDTO updatedDTO) {
        return ResponseEntity.ok(teamMemberService.updateTeamMember(id, updatedDTO));
    }

    // Convenience: allow PATCH to hit the same update method for partial edits (phone/wage)
    @PatchMapping("/{id}")
    public ResponseEntity<TeamMemberDTO> patchTeamMember(@PathVariable Long id,
                                                         @RequestBody TeamMemberDTO partialDTO) {
        return ResponseEntity.ok(teamMemberService.updateTeamMember(id, partialDTO));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteTeamMember(@PathVariable Long id) {
        if (teamMemberService.getTeamMemberById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        teamMemberService.deleteTeamMember(id);
        return ResponseEntity.noContent().build();
    }

    // ✅ Wage history: /api/team/{id}/wage-history?page=0&size=20
    @GetMapping("/{id}/wage-history")
    public ResponseEntity<Page<TeamMemberWageChangeDTO>> getWageHistory(
            @PathVariable Long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        int bounded = Math.min(Math.max(size, 1), 200);
        var pageable = PageRequest.of(page, bounded);
        var pageResult = wageChangeRepository.findByTeamMember_IdOrderByChangedAtDesc(id, pageable)
                .map(wageChangeMapper::toDTO);
        return ResponseEntity.ok(pageResult);
    }
}
