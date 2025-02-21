package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "material_order_items")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class MaterialOrderItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne
    @JoinColumn(name = "material_id", nullable = false)
    private Material material;

    @ManyToOne
    @JoinColumn(name = "order_id", nullable = false)
    private MaterialOrder order;

    @Column(nullable = false)
    private Double quantity;

    @Column(nullable = false)
    private Double cost;
}
