package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.MaterialOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface MaterialOrderRepository extends JpaRepository<MaterialOrder, Long> {
}
