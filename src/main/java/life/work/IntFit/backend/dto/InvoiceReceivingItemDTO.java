// src/main/java/life/work/IntFit/backend/dto/receiving/InvoiceReceivingItemDTO.java
package life.work.IntFit.backend.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InvoiceReceivingItemDTO {
    private int index;       // 1-based
    private String name;     // InvoiceItemDTO.description
    private double qty;      // InvoiceItemDTO.quantity
    private String unit;     // fallback "قطعة" (since InvoiceItemDTO has no unit field)
}
