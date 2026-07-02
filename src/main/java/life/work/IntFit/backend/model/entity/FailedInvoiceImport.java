package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A record of an invoice SMS that could NOT become a PendingInvoice.
 *
 * This exists so failures are reviewable later, instead of only appearing in
 * the upload response summary / server logs (Android ignores the response body).
 *
 * Content storage note: we deliberately store only a truncated preview of the
 * original SMS content plus the parsed URL host (when available), rather than
 * the full raw message. The content can be business-sensitive (Arabic payment
 * text, etc.), and truncation also keeps rows bounded.
 */
@Entity
@Table(name = "failed_invoice_imports")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FailedInvoiceImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The "type" as received from the uploader: "invoice", "status", "unknown", or null. */
    @Column(name = "message_type")
    private String messageType;

    /** Truncated, trimmed preview of the original SMS content (max 512 chars). */
    @Column(name = "content_preview", length = 512)
    private String contentPreview;

    /** Host parsed from the content URL when available (helps identify the source). */
    @Column(name = "url_host")
    private String urlHost;

    /** The raw receivedAt string exactly as received (may be unparseable). */
    @Column(name = "received_at_raw")
    private String receivedAtRaw;

    /** Parsed SMS timestamp when parsing succeeded; null otherwise. */
    @Column(name = "received_at_sms")
    private LocalDateTime receivedAtSms;

    /**
     * Why the import failed. One of:
     * INVALID_INVOICE_URL, INVALID_RECEIVED_AT, PYTHON_PROCESSING_FAILED, UNKNOWN_MESSAGE_TYPE.
     */
    @Column(name = "failure_reason", nullable = false)
    private String failureReason;

    /** Truncated error/exception detail (max 1000 chars), when available. */
    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}