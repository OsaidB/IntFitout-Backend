package life.work.IntFit.backend.repository;


import life.work.IntFit.backend.model.entity.Worksite;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface WorksiteRepository extends JpaRepository<Worksite, Long> {
    Optional<Worksite> findByName(String name);

}
