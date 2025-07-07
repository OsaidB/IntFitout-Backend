package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.StatusMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface StatusMessageRepository extends JpaRepository<StatusMessage, Long> {
}
