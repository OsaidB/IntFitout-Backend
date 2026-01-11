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

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
