// src/main/java/life/work/IntFit/backend/repository/ExtraCostRepository.java
package life.work.IntFit.backend.repository;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
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
    List<ExtraCost> findDatedInRange(@Param("masterId") Long masterId,
                                     @Param("start") LocalDate start,
                                     @Param("end") LocalDate end);

    @Query("""
        select coalesce(sum(e.amount), 0)
        from ExtraCost e
        where e.masterWorksiteId = :masterId
          and e.isGeneral = false
          and e.costDate between :start and :end
    """)
    BigDecimal sumDatedInRange(@Param("masterId") Long masterId,
                               @Param("start") LocalDate start,
                               @Param("end") LocalDate end);

    @Query("""
        select coalesce(sum(e.amount), 0)
        from ExtraCost e
        where e.masterWorksiteId = :masterId
          and (e.isGeneral = true or e.costDate is null)
    """)
    BigDecimal sumGeneral(@Param("masterId") Long masterId);
}

