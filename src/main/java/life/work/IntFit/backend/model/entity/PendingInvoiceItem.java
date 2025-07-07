package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingInvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String description;

    private Double quantity;
    private Double unit_price;
    private Double total_price;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pending_invoice_id")
    private PendingInvoice pendingInvoice;

    @ManyToOne(fetch = FetchType.LAZY, cascade = {CascadeType.PERSIST})
    @JoinColumn(name = "material_id")
    private Material material;
}
