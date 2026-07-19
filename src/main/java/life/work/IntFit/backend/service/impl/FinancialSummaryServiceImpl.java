package life.work.IntFit.backend.service.impl;

import life.work.IntFit.backend.dto.CurrentTotalOwedDTO;
import life.work.IntFit.backend.model.entity.BankCheck;
import life.work.IntFit.backend.repository.BankCheckRepository;
import life.work.IntFit.backend.repository.StatusMessageRepository;
import life.work.IntFit.backend.service.FinancialSummaryService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FinancialSummaryServiceImpl implements FinancialSummaryService {

    private static final int MONEY_SCALE = 2;

    private final StatusMessageRepository statusMessageRepository;
    private final BankCheckRepository bankCheckRepository;
    private final Clock clock;

    @Override
    public CurrentTotalOwedDTO getCurrentTotalOwed() {
        LocalDate today = LocalDate.now(clock);

        BigDecimal debt = computeDebt();
        BigDecimal checksNotDueYet = computeChecksNotDueYet(today);
        BigDecimal total = debt.add(checksNotDueYet);

        return CurrentTotalOwedDTO.builder()
                .debt(scale(debt))
                .checksNotDueYet(scale(checksNotDueYet))
                .totalIncludingChecks(scale(total))
                .calculatedAt(OffsetDateTime.now(clock))
                .build();
    }

    /**
     * Debt = totalOwed of the newest StatusMessage (receivedAt DESC, id DESC).
     * Absent message or null totalOwed -> 0.
     */
    private BigDecimal computeDebt() {
        return statusMessageRepository.findTopByOrderByReceivedAtDescIdDesc()
                .map(m -> m.getTotalOwed() == null
                        ? BigDecimal.ZERO
                        : BigDecimal.valueOf(m.getTotalOwed()))
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Sum of checks that (exactly, for MetricBox parity):
     *   1. fromWhom trimmed, case-insensitive equals "personal"
     *   2. recipientName trimmed, case-insensitive equals "al-etimad"
     *   3. dueDate is non-null
     *   4. dueDate >= today (business date)  -> not overdue; due today is included
     *   5. amount is non-null and positive
     * The cleared field is intentionally NOT considered.
     *
     * Non-overdue candidates (dueDate >= today) are fetched in the DB; the
     * exact trimmed/case-insensitive string checks are done in Java to guarantee
     * parity with the current frontend rule.
     */
    private BigDecimal computeChecksNotDueYet(LocalDate today) {
        List<BankCheck> candidates = bankCheckRepository.findByDueDateGreaterThanEqual(today);

        BigDecimal sum = BigDecimal.ZERO;
        for (BankCheck c : candidates) {
            if (!isPersonal(c)) continue;
            if (!isAlEtimad(c)) continue;

            BigDecimal amount = c.getAmount();
            if (amount == null || amount.signum() <= 0) continue;

            sum = sum.add(amount);
        }
        return sum;
    }

    private boolean isPersonal(BankCheck c) {
        String fromWhom = c.getFromWhom();
        return fromWhom != null && fromWhom.trim().equalsIgnoreCase("personal");
    }

    private boolean isAlEtimad(BankCheck c) {
        String recipientName = c.getRecipientName();
        return recipientName != null && recipientName.trim().equalsIgnoreCase("al-etimad");
    }

    private BigDecimal scale(BigDecimal v) {
        return v.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
