package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;

@Entity
@Table(name = "work_assignments")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkAssignment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "team_member_id")
    private TeamMember teamMember;

//    @ManyToOne(optional = false)
//    @JoinColumn(name = "worksite_id")
//    private Worksite worksite;

    @ManyToOne(optional = false)
    @JoinColumn(name = "master_worksite_id")
    private MasterWorksite masterWorksite;

    @Column(nullable = false)
    private LocalDate date;
}

