package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.CloseoutDraft;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CloseoutDraftRepository extends JpaRepository<CloseoutDraft, Long> {
    Optional<CloseoutDraft> findByMasterWorksite_Id(Long masterWorksiteId);
    boolean existsByMasterWorksite_Id(Long masterWorksiteId);
    void deleteByMasterWorksite_Id(Long masterWorksiteId);
}
