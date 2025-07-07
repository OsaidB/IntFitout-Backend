package life.work.IntFit.backend.dto;

import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PendingInvoiceDTO {

    private Long id;

    private LocalDateTime date;

    private Double netTotal;

    private Double total;

    private String worksiteName;

    private Long worksiteId; // for linking if matched

    private List<PendingInvoiceItemDTO> items;

    private Boolean totalMatch;

    private String pdfUrl;

    private Boolean confirmed;

    private LocalDateTime parsedAt;
}
