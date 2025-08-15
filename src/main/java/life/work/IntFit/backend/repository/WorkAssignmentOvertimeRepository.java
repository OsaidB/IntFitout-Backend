package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.WorkAssignmentOvertime;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorkAssignmentOvertimeRepository extends JpaRepository<WorkAssignmentOvertime, Long> {
    List<WorkAssignmentOvertime> findAllByDate(LocalDate date);

    Optional<WorkAssignmentOvertime> findByTeamMember_IdAndDate(Long teamMemberId, LocalDate date);

    /** Bulk‑delete all overtime rows for a given date. */
    void deleteAllByDate(LocalDate date);
}
