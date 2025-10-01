// src/main/java/life/work/IntFit/backend/model/entity/ExtraCost.java
package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;


@Entity @Table(name="extra_costs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExtraCost {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name="master_worksite_id", nullable=false)
    private Long masterWorksiteId;

    @Column(name="worksite_id")
    private Long worksiteId;

    @Column(name="cost_date")
    private LocalDate costDate; // null => general

    @Column(nullable=false, precision=12, scale=2)
    private BigDecimal amount;

    @Column(nullable=false, columnDefinition="text")
    private String description = "";

    @Column(name="is_general", nullable=false)
    private boolean isGeneral = false;

    @Column(name="created_at", nullable=false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name="updated_at", nullable=false)
    private OffsetDateTime updatedAt = OffsetDateTime.now();

    @PrePersist
    public void onCreate() {
        if (costDate == null) isGeneral = true;
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }
    @PreUpdate
    public void onUpdate() {
        if (costDate == null) isGeneral = true;
        updatedAt = OffsetDateTime.now();
    }

    // getters/setters …
    // (generate with your IDE or Lombok @Data)
}
