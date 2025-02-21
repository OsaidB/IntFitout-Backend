package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Entity
@Table(name = "material_orders")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MaterialOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private LocalDate date;

    @ManyToOne
    @JoinColumn(name = "worksite_id", nullable = false)
    private Worksite worksite;

    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL)
    private List<MaterialOrderItem> items;

    @Column(columnDefinition = "TEXT")
    private String notes;
}
