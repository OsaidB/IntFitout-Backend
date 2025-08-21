package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.PayrollWeek;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDate;
import java.util.Optional;

public interface PayrollWeekRepository extends JpaRepository<PayrollWeek, Long> {
    Optional<PayrollWeek> findByWeekStart(LocalDate weekStart);
}
