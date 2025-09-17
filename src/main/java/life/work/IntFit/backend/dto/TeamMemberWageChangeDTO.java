package life.work.IntFit.backend.dto;

import lombok.*;
import java.time.LocalDateTime;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TeamMemberWageChangeDTO {
    private Long id;
    private Long teamMemberId;
    private Double oldWage;
    private Double newWage;
    private LocalDateTime changedAt;
    private String note;
}
