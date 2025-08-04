package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.dto.MaterialWithUsageDTO;
import life.work.IntFit.backend.model.entity.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

public interface MaterialRepository extends JpaRepository<Material, Long> {
    Optional<Material> findByName(String name);
    Optional<Material> findByNameIgnoreCase(String name);

    @Query("""
    SELECT m.id AS id, m.name AS name, COALESCE(SUM(ii.quantity), 0) AS usageCount
    FROM Material m
    LEFT JOIN InvoiceItem ii ON ii.material.id = m.id
    GROUP BY m.id, m.name
    ORDER BY usageCount DESC
    """)
    List<MaterialWithUsageDTO> findAllWithUsageCount();



}
