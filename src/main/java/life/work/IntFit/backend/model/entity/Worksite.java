package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "worksites")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Worksite {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String location;

    private String status;
    private String manager;
    private String budget;
    private String deadline;
}
