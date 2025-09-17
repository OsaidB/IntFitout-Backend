package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.TeamMemberWageChange;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamMemberWageChangeRepository extends JpaRepository<TeamMemberWageChange, Long> {
    Page<TeamMemberWageChange> findByTeamMember_IdOrderByChangedAtDesc(Long teamMemberId, Pageable pageable);
}
