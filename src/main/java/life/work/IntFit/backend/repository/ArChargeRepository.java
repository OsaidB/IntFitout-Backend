package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.ArCharge;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ArChargeRepository extends JpaRepository<ArCharge, Long> {

    @Query("""
      select c from ArCharge c
      where c.masterWorksiteId = :mid and c.date >= :from and c.date < :toEx
      order by c.date asc, c.id asc
    """)
    List<ArCharge> findInRange(@Param("mid") Long masterId,
                               @Param("from") LocalDate from,
                               @Param("toEx") LocalDate toExclusive);

    @Query("""
      select coalesce(sum(c.amount),0)
      from ArCharge c
      where c.masterWorksiteId = :mid and c.date < :before
    """)
    BigDecimal sumBefore(@Param("mid") Long masterId,
                         @Param("before") LocalDate before);

    @Query("""
      select c from ArCharge c
      where c.masterWorksiteId = :mid and c.date <= :onOrBefore
      order by c.date asc, c.id asc
    """)
    List<ArCharge> findOnOrBefore(@Param("mid") Long masterId,
                                  @Param("onOrBefore") LocalDate onOrBefore);
}
