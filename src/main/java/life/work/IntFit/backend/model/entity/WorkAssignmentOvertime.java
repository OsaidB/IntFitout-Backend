// WorkAssignmentOvertime.java
package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.time.LocalDate;

@Entity
@Table(
        name = "work_assignment_overtime",
        uniqueConstraints = @UniqueConstraint(columnNames = {"team_member_id", "date"})
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class WorkAssignmentOvertime {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    @JoinColumn(name = "team_member_id")
    private TeamMember teamMember;

    @Column(nullable = false)
    private LocalDate date;

    /** Overtime hours in addition to the default 8h (>= 0). */
    @Column(name = "overtime_hours", nullable = false)
    private Double overtimeHours; // was Integer

}
