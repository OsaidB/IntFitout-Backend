package life.work.IntFit.backend.repository;

import jakarta.transaction.Transactional;
import life.work.IntFit.backend.dto.PricePointDTO;
import life.work.IntFit.backend.model.entity.InvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, Long> {
    @Modifying
    @Transactional
    @Query("UPDATE InvoiceItem i SET i.material.id = :targetId WHERE i.material.id = :sourceId")
    int updateMaterialId(@Param("sourceId") Long sourceId, @Param("targetId") Long targetId);

    @Query("""
    SELECT i.date AS date, ii.unit_price AS unitPrice
    FROM InvoiceItem ii
    JOIN ii.invoice i
    WHERE ii.material.id = :materialId AND ii.unit_price IS NOT NULL AND ii.unit_price > 0
    ORDER BY i.date ASC
    """)
    List<PricePointDTO> findPriceHistoryByMaterialId(@Param("materialId") Long materialId);



}
