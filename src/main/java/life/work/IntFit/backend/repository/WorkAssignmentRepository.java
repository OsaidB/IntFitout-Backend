// src/main/java/life/work/IntFit/backend/repository/WorkAssignmentRepository.java
package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.WorkAssignment;
import life.work.IntFit.backend.repository.projection.AssignmentView;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorkAssignmentRepository extends JpaRepository<WorkAssignment, Long> {
    List<WorkAssignment> findByDate(LocalDate date);

    /** Bulk‑delete all assignments for a given date. */
    void deleteByDate(LocalDate date);

    List<WorkAssignment> findByDateBetween(LocalDate from, LocalDate to);

    @EntityGraph(attributePaths = { "masterWorksite", "teamMember" })
    Optional<WorkAssignment> findFirstByMasterWorksite_IdOrderByDateAsc(Long masterWorksiteId);

    @Query("""
        select 
          a.date as date,
          tm.id as teamMemberId,
          tm.name as teamMemberName,
          tm.dailyWage as dailyWage
        from WorkAssignment a
          join a.teamMember tm
        where a.masterWorksite.id = :masterId
          and a.date between :start and :end
    """)
    List<AssignmentView> sliceByMasterAndRange(@Param("masterId") Long masterId,
                                               @Param("start") LocalDate start,
                                               @Param("end") LocalDate end);

    // src/main/java/life/work/IntFit/backend/repository/WorkAssignmentRepository.java
    @EntityGraph(attributePaths = { "masterWorksite", "teamMember" })
    List<WorkAssignment> findAllByDateBetween(LocalDate start, LocalDate end);

}