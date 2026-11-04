package life.work.IntFit.backend.model.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "closeout_final_row")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CloseoutFinalRow {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "type_id", nullable = false)
    private CloseoutFinalType type;

    @Column(name = "row_index", nullable = false)
    private Integer rowIndex;

    @Column(name = "client_row_id", length = 80)
    private String clientRowId;

    @Column(name = "room", length = 255)
    private String room;

    /**
     * The floor this measurement was taken on, as the user typed it, or null when it was
     * never recorded. Free text on purpose: it mirrors `room`, and guessing a floor for a
     * measurement that has none would invent information.
     */
    @Column(name = "floor", length = 80)
    private String floor;

    /**
     * The permanent measurement number from the draft, so a finalized record can still be
     * discussed by the number people saw while measuring. Null for rows finalized before
     * numbering existed; those records are never rewritten.
     */
    @Column(name = "measurement_seq")
    private Long measurementSeq;

    @Column(name = "note", length = 500)
    private String note;

    @Column(name = "mode", length = 30)
    private String mode; // "dims" | "area" (only meaningful for m²)

    // raw inputs normalized to numbers (nullable)
    @Column(name = "length_val", precision = 19, scale = 4)
    private BigDecimal lengthVal;

    @Column(name = "width_val", precision = 19, scale = 4)
    private BigDecimal widthVal;

    @Column(name = "area_val", precision = 19, scale = 4)
    private BigDecimal areaVal;

    @Column(name = "count_val", precision = 19, scale = 4)
    private BigDecimal countVal;

    // computed
    @Column(name = "quantity", precision = 19, scale = 4, nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_price", precision = 19, scale = 2, nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "subtotal", precision = 19, scale = 2, nullable = false)
    private BigDecimal subtotal;
}
