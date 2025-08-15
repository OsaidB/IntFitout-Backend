package life.work.IntFit.backend.dto;

import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MasterWorksiteDTO {
    private Long id;
    private String approvedName;
    private String notes;  // 👈 add this
}
