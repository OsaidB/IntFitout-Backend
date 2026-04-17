package life.work.IntFit.backend.dto;

import life.work.IntFit.backend.model.enums.ProjectSizeTier;
import lombok.*;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class MasterWorksiteDTO {
    private Long id;
    private String approvedName;
    private String notes;

    // Location
    private String city;
    private String area;
    private String subArea;
    private String locationDetails;

    // Project size
    private ProjectSizeTier projectSizeTier;
    private Double estimatedAreaM2;
}
