package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.Instant;

@Entity
@Table(
        name = "closeout_drafts",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_closeout_drafts_master_worksite", columnNames = "master_worksite_id")
        }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CloseoutDraft {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // One draft per MasterWorksite
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(
            name = "master_worksite_id",
            nullable = false,
            foreignKey = @ForeignKey(name = "fk_closeout_drafts_master_worksite")
    )
    @OnDelete(action = OnDeleteAction.CASCADE) // when master worksite is deleted -> draft is deleted
    private MasterWorksite masterWorksite;

    @Lob
    @Column(name = "draft_json", nullable = false, columnDefinition = "LONGTEXT")
    private String draftJson;

    /**
     * Optimistic-lock revision, bumped by the database on every accepted write.
     *
     * A draft is one JSON blob holding every room, type and measurement, so an unchecked
     * write does not merge - it replaces the lot. Hibernate turns this column into
     * "UPDATE ... WHERE id = ? AND version = ?", which is decided atomically by the
     * database. That is deliberately not the same as reading the row, comparing a
     * timestamp in application code and then saving: two writers can both pass that kind
     * of check and the second still wins silently.
     *
     * Device clocks play no part in it.
     */
    @Version
    @Column(name = "version", nullable = false)
    @Builder.Default
    private Long version = 0L;

    /**
     * The next measurement number to hand out for this closeout: the allocation high-water
     * mark.
     *
     * It lives in its own column rather than inside draft_json on purpose. The draft blob is
     * supplied by the client, and an older app build round-trips that blob while dropping
     * fields it does not know about. If the mark lived in the blob, such a client would reset
     * it and the next new measurement would reuse a number already shown to someone. In a
     * column, only the server can move it, and it only ever moves upwards.
     */
    @Column(name = "next_measurement_seq", nullable = false)
    @Builder.Default
    private Long nextMeasurementSeq = 1L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
