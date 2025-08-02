package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.BankCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface BankCheckRepository extends JpaRepository<BankCheck, Long> {
    List<BankCheck> findByDueDateAfterAndClearedFalse(LocalDate today);
    List<BankCheck> findByClearedFalse();
}
