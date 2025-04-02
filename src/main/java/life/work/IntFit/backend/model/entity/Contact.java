package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    private String phone;

    @Enumerated(EnumType.STRING)
    private ContactType type;

    public enum ContactType {
        CLIENT,
        ENGINEER,
        CONTRACTOR,
        DESIGNER,
        SUPERVISOR,
        INSPECTOR,
        FOREMAN,
        SUPPLIER,
        CONSULTANT,
        ARCHITECT
    }
}
