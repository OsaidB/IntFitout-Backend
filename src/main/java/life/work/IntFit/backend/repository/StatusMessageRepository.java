package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.StatusMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.List;
import java.util.Optional;
@Repository
public interface StatusMessageRepository extends JpaRepository<StatusMessage, Long> {

    // Efficient: uses ORDER BY + LIMIT 1
    Optional<StatusMessage> findTopByOrderByReceivedAtDesc();

    // NEW: latest 20 (most recent first)
    List<StatusMessage> findTop20ByOrderByReceivedAtDesc();
}
