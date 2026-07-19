package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.BankCheck;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused H2 tests for {@link BankCheckRepository#findByDueDateGreaterThanEqual}.
 *
 * The query intentionally filters ONLY on dueDate (>= date) and excludes null
 * dueDate at the SQL level; fromWhom / recipientName / cleared / amount rules
 * belong to the service, not this query.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class BankCheckRepositoryTest {

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 17);

    @Autowired
    private BankCheckRepository repository;

    @Autowired
    private TestEntityManager em;

    @Test
    void findByDueDateGreaterThanEqual_returnsTodayAndFuture_excludesPastAndNull() {
        Long yesterdayId = persist(LocalDate.of(2026, 7, 16));
        Long todayId     = persist(LocalDate.of(2026, 7, 17));
        Long tomorrowId  = persist(LocalDate.of(2026, 7, 18));
        Long nullDueId   = persist(null);

        List<BankCheck> result = repository.findByDueDateGreaterThanEqual(BUSINESS_DATE);

        assertThat(result).extracting(BankCheck::getId)
                .contains(todayId, tomorrowId)
                .doesNotContain(yesterdayId, nullDueId);
    }

    /** Minimal valid BankCheck: amount is the only non-null-constrained money field. */
    private Long persist(LocalDate dueDate) {
        BankCheck check = BankCheck.builder()
                .amount(new BigDecimal("1.00"))
                .dueDate(dueDate)
                .build();
        return em.persistAndGetId(check, Long.class);
    }
}
