package life.work.IntFit.backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingInvoiceItemDTO {

    private Long id;

    private String description;

    private Double quantity;
    private Double unit_price;
    private Double total_price;

    private Long materialId;
    // private String materialName; // Uncomment if you want to support name-based lookup
}
