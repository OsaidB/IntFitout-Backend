package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.Invoice;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InvoiceRepository extends JpaRepository<Invoice, Long> {
    List<Invoice> findByWorksiteId(Long worksiteId);
}
