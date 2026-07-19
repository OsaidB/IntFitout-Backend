package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.BankCheck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface BankCheckRepository extends JpaRepository<BankCheck, Long> {
    List<BankCheck> findByDueDateAfterAndClearedFalse(LocalDate today);
    List<BankCheck> findByClearedFalse();

    /**
     * Non-overdue checks: dueDate >= the given business date.
     * Rows with a null dueDate are excluded by SQL (NULL >= ? is not true).
     * Used by the canonical financial-summary "checks not due yet" metric,
     * which applies the exact Personal/Al-Etimad/amount predicates in Java.
     */
    List<BankCheck> findByDueDateGreaterThanEqual(LocalDate date);
}
