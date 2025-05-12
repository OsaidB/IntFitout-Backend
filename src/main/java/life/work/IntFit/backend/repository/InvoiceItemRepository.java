package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.InvoiceItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InvoiceItemRepository extends JpaRepository<InvoiceItem, Long> {
}
