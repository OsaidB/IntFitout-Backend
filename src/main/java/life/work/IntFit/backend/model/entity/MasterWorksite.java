package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
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

    @OneToMany(mappedBy = "masterWorksite", cascade = CascadeType.ALL)
    private List<Worksite> subWorksites;
}
