package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.Material;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

public interface MaterialRepository extends JpaRepository<Material, Long> {
    Optional<Material> findByName(String name);
    Optional<Material> findByNameIgnoreCase(String name);



}
