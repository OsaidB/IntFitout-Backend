package life.work.IntFit.backend.repository;

import jakarta.transaction.Transactional;
import life.work.IntFit.backend.model.entity.InvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, Long> {
    @Modifying
    @Transactional
    @Query("UPDATE InvoiceItem i SET i.material.id = :targetId WHERE i.material.id = :sourceId")
    int updateMaterialId(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId);
}
