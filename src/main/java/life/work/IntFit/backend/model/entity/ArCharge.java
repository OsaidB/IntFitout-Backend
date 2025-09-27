package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "ar_charge")
@Data @NoArgsConstructor @AllArgsConstructor @Builder
public class ArCharge {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="master_worksite_id", nullable=false)
    private Long masterWorksiteId;

    @Column(name="date", nullable=false)
    private LocalDate date;

    @Column(length=255)
    private String description;

    @Column(nullable=false, precision=12, scale=2)
    private BigDecimal amount;

    @Column(name="created_at", nullable=false)
    private Instant createdAt = Instant.now();
}
