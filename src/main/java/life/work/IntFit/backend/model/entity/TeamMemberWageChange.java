// src/main/java/life/work/IntFit/backend/model/entity/TeamMemberWageChange.java
package life.work.IntFit.backend.model.entity;

import lombok.*;
import jakarta.persistence.*;   // ⬅️ jakarta, not javax
import java.time.LocalDateTime;

@Entity
@Table(
        name = "team_member_wage_change",
        indexes = {
                @Index(name = "idx_wage_change_member", columnList = "team_member_id"),
                @Index(name = "idx_wage_change_member_time", columnList = "team_member_id,changed_at")
        }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeamMemberWageChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_member_id", nullable = false)
    private TeamMember teamMember;

    @Column(name = "old_wage")
    private Double oldWage;

    @Column(name = "new_wage", nullable = false)
    private Double newWage;

    @Column(name = "changed_at", nullable = false)
    private LocalDateTime changedAt;

    @Column(name = "note", length = 255)
    private String note;
}
