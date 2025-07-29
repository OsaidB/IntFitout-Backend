package life.work.IntFit.backend.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InvoiceDTO {

    private Long id;

    private LocalDateTime date;

    private Double netTotal;

    private Double total;

    private Long worksiteId;       // used when worksite already exists
    private String worksiteName;   // used to create or find worksite if ID is missing

    private List<InvoiceItemDTO> items;

    private Boolean total_match;

    private String pdfUrl;
    private LocalDateTime parsedAt;
    private Long reprocessedFromId;

}
