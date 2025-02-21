package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.MaterialUsage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface MaterialUsageRepository extends JpaRepository<MaterialUsage, Long> {
    List<MaterialUsage> findByDate(LocalDate date);
    List<MaterialUsage> findByWorksiteId(Long worksiteId);
}
