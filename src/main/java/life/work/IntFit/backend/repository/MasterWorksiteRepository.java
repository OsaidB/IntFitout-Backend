package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.MasterWorksite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface MasterWorksiteRepository extends JpaRepository<MasterWorksite, Long> {
    Optional<MasterWorksite> findByApprovedNameIgnoreCase(String name);
    boolean existsByApprovedNameIgnoreCase(String approvedName);

}
