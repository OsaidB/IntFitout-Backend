package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import life.work.IntFit.backend.model.enums.StatusType;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(
        indexes = {
                @Index(name = "idx_status_received_at", columnList = "receivedAt"),
                @Index(name = "idx_status_worksite_id", columnList = "worksite_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"worksite"})
public class StatusMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Full raw SMS text */
    @Lob
    @Column(nullable = false)
    private String content;

    /** When the SMS was received (device time, from native app) */
    @Column(nullable = false)
    private LocalDateTime receivedAt;

    /** Parsed numeric amount if present (e.g., RETURN / PAYMENT). For RETURN we store positive and flip to negative on apply. */
    private Double amount;

    /** “رصيدكم …” total owed parsed from the message, if present */
    private Double totalOwed;

    /** Classified type (ORDER_ISSUED, RETURN, PAYMENT, BALANCE_AT_DATE, UNKNOWN) */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StatusType statusType;

    /** For “رصيدكم لغاية …” messages */
    private LocalDate balanceDate;

    /* ----------------------------
       Assignment to a Worksite
       ---------------------------- */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "worksite_id")
    private Worksite worksite; // optional; set via /status-messages/{id}/assign/{worksiteId}

    /* ----------------------------
       Application as Adjustment
       ---------------------------- */
    /** The created invoice id when we apply this message as a negative invoice */
    private Long appliedInvoiceId;

    /** When we applied it */
    private LocalDateTime appliedAt;

    /** Amount actually applied (negative for expense reduction) */
    private Double appliedAmount;

    /** Optional note from UI when applying */
    @Column(length = 400)
    private String appliedNote;
}
