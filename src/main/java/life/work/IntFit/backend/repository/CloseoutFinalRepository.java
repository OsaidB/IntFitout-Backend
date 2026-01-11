package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.CloseoutFinal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CloseoutFinalRepository extends JpaRepository<CloseoutFinal, Long> {
    Optional<CloseoutFinal> findTopByMasterWorksite_IdOrderByFinalizedAtDesc(Long masterWorksiteId);
}
