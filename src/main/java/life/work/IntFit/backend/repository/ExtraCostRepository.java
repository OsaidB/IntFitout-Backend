// src/main/java/life/work/IntFit/backend/repository/ExtraCostRepository.java
package life.work.IntFit.backend.repository;

import org.springframework.data.jpa.repository.*;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import life.work.IntFit.backend.model.entity.ExtraCost;
@Repository
public interface ExtraCostRepository extends JpaRepository<ExtraCost, Long> {

    // Dated by specific day (frontend filters by master on client; master optional helps reduce payload)
    @Query("""
      select e from ExtraCost e
      where e.costDate = :date
        and (:masterId is null or e.masterWorksiteId = :masterId)
    """)
    List<ExtraCost> findByDate(LocalDate date, Long masterId);

    // General (no-date) by master
    List<ExtraCost> findByMasterWorksiteIdAndIsGeneralTrueOrderByIdDesc(Long masterId);

    // Dated range
    @Query("""
      select e from ExtraCost e
      where e.masterWorksiteId = :masterId
        and e.costDate is not null
        and e.costDate between :start and :end
      order by e.costDate desc, e.id desc
    """)
    List<ExtraCost> findDatedInRange(Long masterId, LocalDate start, LocalDate end);
}
