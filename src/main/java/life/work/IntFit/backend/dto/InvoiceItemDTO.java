package life.work.IntFit.backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceItemDTO {

    private Long id;

    private String description;

//    private String unit;
    private Double quantity;
    private Double unit_price;
    private Double total_price;

    private Long materialId;
//    private String materialName; // for easier material lookup or creation

}
