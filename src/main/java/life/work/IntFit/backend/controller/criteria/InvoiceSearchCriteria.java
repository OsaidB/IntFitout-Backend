package life.work.IntFit.backend.controller.criteria;

// package life.work.IntFit.backend.dto (or .model)
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class InvoiceSearchCriteria {
    private String fromDate;       // ISO-8601 LocalDateTime string
    private String toDate;         // ISO-8601 LocalDateTime string
    private Long worksiteId;
    private Long masterWorksiteId;
    private String q;              // free-text
}
