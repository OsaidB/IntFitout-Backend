package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.WorkAssignmentOvertime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WorkAssignmentOvertimeRepository extends JpaRepository<WorkAssignmentOvertime, Long> {

    List<WorkAssignmentOvertime> findAllByDate(LocalDate date);

    Optional<WorkAssignmentOvertime> findByTeamMember_IdAndDate(Long teamMemberId, LocalDate date);

    void deleteAllByDate(LocalDate date);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Transactional
    @Query(value = """
        INSERT INTO work_assignment_overtime (team_member_id, date, overtime_hours)
        VALUES (:teamMemberId, :date, :hours)
        ON DUPLICATE KEY UPDATE overtime_hours = VALUES(overtime_hours)
        """, nativeQuery = true)
    void upsert(@Param("teamMemberId") Long teamMemberId,
                @Param("date") LocalDate date,
                @Param("hours") Double hours); // was Integer

    @Modifying
    @Transactional
    @Query("delete from WorkAssignmentOvertime w where w.teamMember.id = :teamMemberId and w.date = :date")
    void deleteByMemberAndDate(@Param("teamMemberId") Long teamMemberId,
                               @Param("date") LocalDate date);

    @Query("""
    select o
    from WorkAssignmentOvertime o
    where o.teamMember.id in :memberIds
      and o.date between :start and :end
    """)
    List<WorkAssignmentOvertime> findForMembersInRange(@Param("memberIds") List<Long> memberIds,
                                                       @Param("start") LocalDate start,
                                                       @Param("end") LocalDate end);


    @Query("""
    select o from WorkAssignmentOvertime o
    join fetch o.teamMember tm
    where o.date between :start and :end
    """)
    List<WorkAssignmentOvertime> findAllByDateBetween(
            @Param("start") LocalDate start,
            @Param("end") LocalDate end
    );


}
