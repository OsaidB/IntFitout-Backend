package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "team_members")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class TeamMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String role;

    private String experience;
    private String contact;

    @Column(name = "daily_wage")
    private Double dailyWage;  // New field representing pay per day for construction work
}
