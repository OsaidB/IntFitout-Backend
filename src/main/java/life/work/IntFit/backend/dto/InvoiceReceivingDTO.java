// src/main/java/life/work/IntFit/backend/dto/receiving/InvoiceReceivingDTO.java
package life.work.IntFit.backend.dto;

import lombok.*;
import java.time.LocalDateTime;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InvoiceReceivingDTO {
    private Long invoiceId;
    private String supplier;
    private LocalDateTime date;          // from Invoice (LocalDateTime)
    private Long masterWorksiteId;       // resolved from Worksite → MasterWorksite
    private String masterWorksiteName;   // fallback to invoice.worksiteName if no master
    private String worksiteName;         // original child name, for reference
    private List<InvoiceReceivingItemDTO> items;
}
