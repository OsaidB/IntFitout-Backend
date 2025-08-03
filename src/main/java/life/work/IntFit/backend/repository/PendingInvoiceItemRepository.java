package life.work.IntFit.backend.repository;

import jakarta.transaction.Transactional;
import life.work.IntFit.backend.model.entity.PendingInvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PendingInvoiceItemRepository extends JpaRepository<PendingInvoiceItem, Long> {
    @Modifying
    @Transactional
    @Query("UPDATE PendingInvoiceItem p SET p.material.id = :targetId WHERE p.material.id = :sourceId")
    int updateMaterialId(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId);
}
