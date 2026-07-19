package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.StatusMessage;
import life.work.IntFit.backend.model.enums.StatusType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Focused H2 tests for
 * {@link StatusMessageRepository#findTopByOrderByReceivedAtDescIdDesc()}.
 *
 * Ordering contract: receivedAt DESC, then id DESC (highest id wins on ties).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class StatusMessageRepositoryTest {

    @Autowired
    private StatusMessageRepository repository;

    @Autowired
    private TestEntityManager em;

    @Test
    void returnsMessageWithLatestReceivedAt() {
        persist(LocalDateTime.of(2026, 7, 15, 10, 0), 100.0);
        Long newestId = persist(LocalDateTime.of(2026, 7, 17, 10, 0), 200.0);

        Optional<StatusMessage> result = repository.findTopByOrderByReceivedAtDescIdDesc();

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(newestId);
        assertThat(result.get().getTotalOwed()).isEqualTo(200.0);
    }

    @Test
    void onEqualReceivedAt_returnsHighestId() {
        LocalDateTime sameInstant = LocalDateTime.of(2026, 7, 17, 12, 0);
        Long firstId  = persist(sameInstant, 111.0);
        Long secondId = persist(sameInstant, 222.0);

        assertThat(secondId).isGreaterThan(firstId); // IDENTITY assigns higher id to the later insert

        Optional<StatusMessage> result = repository.findTopByOrderByReceivedAtDescIdDesc();

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(secondId);
        assertThat(result.get().getTotalOwed()).isEqualTo(222.0);
    }

    @Test
    void emptyRepositoryReturnsEmptyOptional() {
        assertThat(repository.findTopByOrderByReceivedAtDescIdDesc()).isEmpty();
    }

    /** Minimal valid StatusMessage: content, receivedAt, statusType are non-null. */
    private Long persist(LocalDateTime receivedAt, Double totalOwed) {
        StatusMessage message = StatusMessage.builder()
                .content("test")
                .receivedAt(receivedAt)
                .statusType(StatusType.BALANCE_AT_DATE)
                .totalOwed(totalOwed)
                .build();
        return em.persistAndGetId(message, Long.class);
    }
}
