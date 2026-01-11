package life.work.IntFit.backend.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "closeout_final_type")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CloseoutFinalType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "closeout_final_id", nullable = false)
    private CloseoutFinal closeoutFinal;

    @Column(name = "type_index", nullable = false)
    private Integer typeIndex;

    @Column(name = "client_type_id", length = 80)
    private String clientTypeId;

    @Column(name = "name", nullable = false, length = 255)
    private String name;

    @Column(name = "unit", nullable = false, length = 20)
    private String unit; // "m", "m²", "count"

    @Column(name = "category", nullable = false, length = 30)
    private String category; // "gypsum" | "painting"

    @Column(name = "unit_price", precision = 19, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "quantity_total", precision = 19, scale = 4, nullable = false)
    private BigDecimal quantityTotal;

    @Column(name = "subtotal", precision = 19, scale = 2, nullable = false)
    private BigDecimal subtotal;

    @OneToMany(mappedBy = "type", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<CloseoutFinalRow> rows = new ArrayList<>();
}
