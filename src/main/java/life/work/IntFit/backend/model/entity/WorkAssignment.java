package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(
        name = "work_assignments"
        // If you plan to enforce “one member–site per day”, add this after doing a DB migration:
        // , uniqueConstraints = @UniqueConstraint(
        //     name = "uk_day_member_site",
        //     columnNames = {"date","team_member_id","master_worksite_id"}
        // )
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // ✅ LAZY avoids eager loads unless you explicitly fetch-join
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "team_member_id", nullable = false)
    private TeamMember teamMember;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "master_worksite_id", nullable = false)
    private MasterWorksite masterWorksite;

    @Column(nullable = false)
    private LocalDate date;
}

