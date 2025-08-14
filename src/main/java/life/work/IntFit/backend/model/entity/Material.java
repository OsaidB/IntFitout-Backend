package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import life.work.IntFit.backend.model.enums.MaterialCategory;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Material {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private boolean newlyAdded = true;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private MaterialCategory category = MaterialCategory.OTHER;
}
