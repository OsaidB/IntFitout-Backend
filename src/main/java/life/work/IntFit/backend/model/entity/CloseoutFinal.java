package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "closeout_final")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CloseoutFinal {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // If your MasterWorksite entity/package differs, adjust the import/type
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "master_worksite_id", nullable = false)
    private MasterWorksite masterWorksite;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "finalized_at", nullable = false)
    private Instant finalizedAt;

    @Column(name = "draft_updated_at")
    private Instant draftUpdatedAt;

    @Lob
    @Column(name = "draft_snapshot_json", columnDefinition = "LONGTEXT")
    private String draftSnapshotJson;

    @Column(name = "gypsum_total", precision = 19, scale = 2, nullable = false)
    private BigDecimal gypsumTotal;

    @Column(name = "painting_total", precision = 19, scale = 2, nullable = false)
    private BigDecimal paintingTotal;

    @Column(name = "grand_total", precision = 19, scale = 2, nullable = false)
    private BigDecimal grandTotal;

    @OneToMany(mappedBy = "closeoutFinal", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CloseoutFinalType> types = new ArrayList<>();
}
