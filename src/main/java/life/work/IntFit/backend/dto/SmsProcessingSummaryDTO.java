package life.work.IntFit.backend.dto;

import lombok.*;

/**
 * Result summary returned by the SMS upload endpoint so that per-message
 * outcomes (processed / skipped / failed) are visible to the caller and logs,
 * instead of being silently swallowed.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString
public class SmsProcessingSummaryDTO {

    private int totalMessages;

    private int invoiceMessages;
    private int statusMessages;
    private int unknownTypeMessages;

    private int invoicesProcessed;
    private int invoicesSkippedInvalidUrl;
    private int invoicesFailed;

    private int statusProcessed;
    private int statusFailed;

    /** Number of failed-import rows actually persisted to failed_invoice_imports for this batch. */
    private int failedImportsRecorded;
}