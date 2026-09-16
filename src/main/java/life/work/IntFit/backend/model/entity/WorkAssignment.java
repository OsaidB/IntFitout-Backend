package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import life.work.IntFit.backend.service.allocation.DecimalHours;
import lombok.*;

import java.math.BigDecimal;
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

    /**
     * Custom regular hours for this worksite. Null means the member's regular
     * hours are split equally (AUTO). Within one member's day either every
     * row carries a value or none does.
     */
    @Column(name = "regular_hours_override",
            precision = DecimalHours.STORAGE_PRECISION, scale = DecimalHours.STORAGE_SCALE)
    private BigDecimal regularHoursOverride;

    /** Custom overtime hours for this worksite; same rules as {@link #regularHoursOverride}. */
    @Column(name = "overtime_hours_override",
            precision = DecimalHours.STORAGE_PRECISION, scale = DecimalHours.STORAGE_SCALE)
    private BigDecimal overtimeHoursOverride;
}

