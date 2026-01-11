package life.work.IntFit.backend.dto;

import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@Builder
public class CloseoutFinalizeResponseDTO {
    private Long closeoutFinalId;
    private Long masterWorksiteId;

    private BigDecimal gypsumTotal;
    private BigDecimal paintingTotal;
    private BigDecimal grandTotal;

    private Instant finalizedAt;
}
