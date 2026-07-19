package life.work.IntFit.backend.service.impl;

import life.work.IntFit.backend.config.TimeConfig;
import life.work.IntFit.backend.dto.CurrentTotalOwedDTO;
import life.work.IntFit.backend.model.entity.BankCheck;
import life.work.IntFit.backend.model.entity.StatusMessage;
import life.work.IntFit.backend.repository.BankCheckRepository;
import life.work.IntFit.backend.repository.StatusMessageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link FinancialSummaryServiceImpl} using mocked repositories
 * and a fixed Clock whose Asia/Hebron business date is 2026-07-17.
 *
 * Repository ordering (receivedAt DESC, id DESC) is a repository contract and is
 * exercised in StatusMessageRepositoryTest; here we only assert the service uses
 * the deterministic finder and the correct business date.
 */
@ExtendWith(MockitoExtension.class)
class FinancialSummaryServiceImplTest {

    /** 2026-07-17T13:00 in Asia/Hebron (summer offset). Business date = 2026-07-17. */
    private static final Instant FIXED_INSTANT =
            OffsetDateTime.of(2026, 7, 17, 13, 0, 0, 0, ZoneOffset.ofHours(3)).toInstant();

    private static final LocalDate BUSINESS_DATE = LocalDate.of(2026, 7, 17);

    private final Clock clock = Clock.fixed(FIXED_INSTANT, TimeConfig.BUSINESS_ZONE);

    @Mock
    private StatusMessageRepository statusMessageRepository;

    @Mock
    private BankCheckRepository bankCheckRepository;

    private FinancialSummaryServiceImpl service() {
        return new FinancialSummaryServiceImpl(statusMessageRepository, bankCheckRepository, clock);
    }

    // ---------------------------------------------------------------------
    // Debt
    // ---------------------------------------------------------------------

    @Test
    void debt_usesNewestStatusMessageTotalOwed() {
        when(statusMessageRepository.findTopByOrderByReceivedAtDescIdDesc())
                .thenReturn(Optional.of(statusMessage(370246.0)));
        when(bankCheckRepository.findByDueDateGreaterThanEqual(any())).thenReturn(List.of());

        CurrentTotalOwedDTO result = service().getCurrentTotalOwed();

        assertThat(result.getDebt()).isEqualByComparingTo("370246.00");
        verify(statusMessageRepository).findTopByOrderByReceivedAtDescIdDesc();
    }

    @Test
    void debt_isZeroWhenNoStatusMessage() {
        when(statusMessageRepository.findTopByOrderByReceivedAtDescIdDesc()).thenReturn(Optional.empty());
        when(bankCheckRepository.findByDueDateGreaterThanEqual(any())).thenReturn(List.of());

        CurrentTotalOwedDTO result = service().getCurrentTotalOwed();

        assertThat(result.getDebt()).isEqualByComparingTo("0.00");
        assertThat(result.getDebt().scale()).isEqualTo(2);
    }

    @Test
    void debt_isZeroWhenTotalOwedIsNull() {
        when(statusMessageRepository.findTopByOrderByReceivedAtDescIdDesc())
                .thenReturn(Optional.of(statusMessage(null)));
        when(bankCheckRepository.findByDueDateGreaterThanEqual(any())).thenReturn(List.of());

        CurrentTotalOwedDTO result = service().getCurrentTotalOwed();

        assertThat(result.getDebt()).isEqualByComparingTo("0.00");
    }

    @Test
    void debt_isScaledToTwoDecimalsHalfUp() {
        // 10.125 -> HALF_UP -> 10.13 (HALF_EVEN would give 10.12)
        when(statusMessageRepository.findTopByOrderByReceivedAtDescIdDesc())
                .thenReturn(Optional.of(statusMessage(10.125)));
        when(bankCheckRepository.findByDueDateGreaterThanEqual(any())).thenReturn(List.of());

        CurrentTotalOwedDTO result = service().getCurrentTotalOwed();

        assertThat(result.getDebt()).isEqualTo(new BigDecimal("10.13"));
    }

    // ---------------------------------------------------------------------
    // Checks — exact service predicates
    // ---------------------------------------------------------------------

    @Test
    void checks_includePersonalAlEtimadPositiveAmount() {
        assertThat(checksSumFor(check("Personal", "Al-Etimad", "70000", false)))
                .isEqualByComparingTo("70000");
    }

    @Test
    void checks_includeDespiteDifferentCaseAndWhitespace() {
        assertThat(checksSumFor(check("  pErSoNaL  ", "  al-ETIMAD ", "123.45", false)))
                .isEqualByComparingTo("123.45");
    }

    @Test
    void checks_excludeNonPersonal() {
        assertThat(checksSumFor(check("Company", "Al-Etimad", "500", false)))
                .isEqualByComparingTo("0");
    }

    @Test
    void checks_excludeOtherRecipient() {
        assertThat(checksSumFor(check("Personal", "Some Supplier", "500", false)))
                .isEqualByComparingTo("0");
    }

    @Test
    void checks_excludeNullFromWhom() {
        assertThat(checksSumFor(check(null, "Al-Etimad", "500", false)))
                .isEqualByComparingTo("0");
    }

    @Test
    void checks_excludeNullRecipient() {
        assertThat(checksSumFor(check("Personal", null, "500", false)))
                .isEqualByComparingTo("0");
    }

    @Test
    void checks_excludeNullAmount() {
        assertThat(checksSumFor(check("Personal", "Al-Etimad", null, false)))
                .isEqualByComparingTo("0");
    }

    @Test
    void checks_excludeZeroAmount() {
        assertThat(checksSumFor(check("Personal", "Al-Etimad", "0", false)))
                .isEqualByComparingTo("0");
    }

    @Test
    void checks_excludeNegativeAmount() {
        assertThat(checksSumFor(check("Personal", "Al-Etimad", "-100", false)))
                .isEqualByComparingTo("0");
    }

    @Test
    void checks_includeWhenClearedTrue() {
        assertThat(checksSumFor(check("Personal", "Al-Etimad", "70000", true)))
                .isEqualByComparingTo("70000");
    }

    @Test
    void checks_includeWhenClearedFalse() {
        assertThat(checksSumFor(check("Personal", "Al-Etimad", "70000", false)))
                .isEqualByComparingTo("70000");
    }

    @Test
    void checks_queryUsesBusinessDate() {
        when(statusMessageRepository.findTopByOrderByReceivedAtDescIdDesc()).thenReturn(Optional.empty());
        when(bankCheckRepository.findByDueDateGreaterThanEqual(BUSINESS_DATE)).thenReturn(List.of());

        service().getCurrentTotalOwed();

        verify(bankCheckRepository).findByDueDateGreaterThanEqual(BUSINESS_DATE);
    }

    // ---------------------------------------------------------------------
    // Summary
    // ---------------------------------------------------------------------

    @Test
    void summary_totalIsDebtPlusChecks_scaledTwoDecimals_withBusinessOffset() {
        when(statusMessageRepository.findTopByOrderByReceivedAtDescIdDesc())
                .thenReturn(Optional.of(statusMessage(370246.0)));
        when(bankCheckRepository.findByDueDateGreaterThanEqual(BUSINESS_DATE))
                .thenReturn(List.of(
                        check("Personal", "Al-Etimad", "50000", false),
                        check("Personal", "Al-Etimad", "20000", true)   // cleared still counts
                ));

        CurrentTotalOwedDTO result = service().getCurrentTotalOwed();

        assertThat(result.getDebt()).isEqualByComparingTo("370246.00");
        assertThat(result.getChecksNotDueYet()).isEqualByComparingTo("70000.00");
        assertThat(result.getTotalIncludingChecks()).isEqualByComparingTo("440246.00");

        assertThat(result.getDebt().scale()).isEqualTo(2);
        assertThat(result.getChecksNotDueYet().scale()).isEqualTo(2);
        assertThat(result.getTotalIncludingChecks().scale()).isEqualTo(2);

        OffsetDateTime expectedCalculatedAt = OffsetDateTime.ofInstant(FIXED_INSTANT, TimeConfig.BUSINESS_ZONE);
        assertThat(result.getCalculatedAt()).isEqualTo(expectedCalculatedAt);
        assertThat(result.getCalculatedAt().getOffset())
                .isEqualTo(TimeConfig.BUSINESS_ZONE.getRules().getOffset(FIXED_INSTANT));

        verify(statusMessageRepository, times(1)).findTopByOrderByReceivedAtDescIdDesc();
        verify(bankCheckRepository, times(1)).findByDueDateGreaterThanEqual(BUSINESS_DATE);
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /** Runs the service over a single candidate check and returns checksNotDueYet. */
    private BigDecimal checksSumFor(BankCheck candidate) {
        when(statusMessageRepository.findTopByOrderByReceivedAtDescIdDesc()).thenReturn(Optional.empty());
        when(bankCheckRepository.findByDueDateGreaterThanEqual(BUSINESS_DATE))
                .thenReturn(List.of(candidate));
        return service().getCurrentTotalOwed().getChecksNotDueYet();
    }

    private static StatusMessage statusMessage(Double totalOwed) {
        return StatusMessage.builder().totalOwed(totalOwed).build();
    }

    private static BankCheck check(String fromWhom, String recipientName, String amount, boolean cleared) {
        return BankCheck.builder()
                .fromWhom(fromWhom)
                .recipientName(recipientName)
                .amount(amount == null ? null : new BigDecimal(amount))
                .cleared(cleared)
                .build();
    }
}
