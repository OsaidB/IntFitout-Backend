// File: src/main/java/life/work/IntFit/backend/dto/MasterWorksiteStartSummaryDTO.java
package life.work.IntFit.backend.dto;

import lombok.*;
import java.time.LocalDate;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class MasterWorksiteStartSummaryDTO {
    private Long masterWorksiteId;

    // Earliest invoice
    private LocalDate firstInvoiceDate;
    private Long firstInvoiceId;
    private Long firstInvoiceWorksiteId;

    // Earliest assignment
    private LocalDate firstAssignmentDate;
    private Long firstAssignmentId;
    private Long firstAssignmentWorksiteId;

    // Computed start
    private LocalDate startDate;     // min(firstInvoiceDate, firstAssignmentDate)
    private String   startSource;    // "INVOICE" | "ASSIGNMENT" | "NONE"
}
