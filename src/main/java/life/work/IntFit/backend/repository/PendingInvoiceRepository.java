package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.PendingInvoice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PendingInvoiceRepository extends JpaRepository<PendingInvoice, Long> {

    List<PendingInvoice> findByConfirmedFalse();

    List<PendingInvoice> findTop20ByOrderByParsedAtDesc();

    List<PendingInvoice> findByWorksiteIdAndConfirmedFalse(Long worksiteId);
}
