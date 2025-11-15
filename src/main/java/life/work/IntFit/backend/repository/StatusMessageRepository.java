package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.StatusMessage;
import life.work.IntFit.backend.model.enums.StatusType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface StatusMessageRepository extends JpaRepository<StatusMessage, Long> {

    // Efficient latest one
    Optional<StatusMessage> findTopByOrderByReceivedAtDesc();

    // Latest 20, eager worksite for UI
    @EntityGraph(attributePaths = {"worksite"})
    List<StatusMessage> findTop20ByOrderByReceivedAtDesc();

    // ---- Used by your controller ----

    // All messages with worksite eager, newest first
    @Query("""
        SELECT sm FROM StatusMessage sm
        LEFT JOIN FETCH sm.worksite
        ORDER BY sm.receivedAt DESC
    """)
    List<StatusMessage> findAllWithWorksiteOrderByReceivedAtDesc();

    // Only unassigned, eager, newest first
    @Query("""
        SELECT sm FROM StatusMessage sm
        LEFT JOIN FETCH sm.worksite
        WHERE sm.worksite IS NULL
        ORDER BY sm.receivedAt DESC
    """)
    List<StatusMessage> findUnassignedOrderByReceivedAtDesc();

    // By worksite, newest first (with worksite eager to avoid N+1)
    @EntityGraph(attributePaths = {"worksite"})
    List<StatusMessage> findByWorksite_IdOrderByReceivedAtDesc(Long worksiteId);

    // 🔹 Latest BALANCE message (change BALANCE if your enum uses a different name)
    Optional<StatusMessage> findTopByStatusTypeOrderByReceivedAtDesc(StatusType statusType);

    // 🔹 All messages from a given anchor time (including worksite), newest first
    @Query("""
        SELECT sm
        FROM StatusMessage sm
        LEFT JOIN FETCH sm.worksite
        WHERE sm.receivedAt >= :anchor
        ORDER BY sm.receivedAt DESC
    """)
    List<StatusMessage> findSinceAnchorWithWorksite(@Param("anchor") LocalDateTime anchor);
}
