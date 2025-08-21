package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.WorkAssignment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface WorkAssignmentRepository extends JpaRepository<WorkAssignment, Long> {
    List<WorkAssignment> findByDate(LocalDate date);

    /** Bulk‑delete all assignments for a given date. */
    void deleteByDate(LocalDate date);

    List<WorkAssignment> findByDateBetween(LocalDate from, LocalDate to);

}