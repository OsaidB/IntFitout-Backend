package life.work.IntFit.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Canonical "Current Total Owed" financial snapshot.
 *
 * All monetary values are BigDecimal at scale 2. calculatedAt is an ISO-8601
 * offset timestamp in the business timezone (Asia/Hebron).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CurrentTotalOwedDTO {

    /** Newest StatusMessage.totalOwed, or 0.00 when absent. */
    private BigDecimal debt;

    /** Sum of qualifying Personal -> Al-Etimad checks with dueDate >= today. */
    private BigDecimal checksNotDueYet;

    /** debt + checksNotDueYet. */
    private BigDecimal totalIncludingChecks;

    /** When these values were computed, in the business timezone. */
    private OffsetDateTime calculatedAt;
}
