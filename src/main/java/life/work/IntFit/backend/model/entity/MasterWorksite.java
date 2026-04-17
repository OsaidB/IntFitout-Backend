package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import life.work.IntFit.backend.model.enums.ProjectSizeTier;
import lombok.*;

import java.util.List;

@Entity
@Table(name = "master_worksites")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MasterWorksite {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String approvedName;

    private String notes;

    // Location
    private String city;
    private String area;
    private String subArea;
    private String locationDetails;

    // Project size
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ProjectSizeTier projectSizeTier;

    private Double estimatedAreaM2;

    @OneToMany(mappedBy = "masterWorksite", cascade = CascadeType.ALL)
    private List<Worksite> subWorksites;
}
