package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "medical_sessions")
public class MedicalSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String diagnosis;

    @Column(nullable = false)
    private String treatment;

    @Column(nullable = false)
    private LocalDateTime sessionDate;

    @ManyToOne
    @JoinColumn(name = "pet_id", nullable = false)
    private Pet pet; // Link the session to a specific pet

    @ManyToOne
    @JoinColumn(name = "vet_id", nullable = false)
    private User veterinarian; // Link the session to a specific veterinarian
}
