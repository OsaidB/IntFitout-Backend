package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.FailedInvoiceImport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface FailedInvoiceImportRepository extends JpaRepository<FailedInvoiceImport, Long> {

    // Recent failures, newest first. Pageable is used to cap how many are returned.
    List<FailedInvoiceImport> findAllByOrderByCreatedAtDesc(Pageable pageable);
}