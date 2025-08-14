package life.work.IntFit.backend.dto;

import lombok.*;
import life.work.IntFit.backend.model.enums.MaterialCategory;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaterialDTO {
    private Long id;
    private String name;
    private boolean newlyAdded;
    private MaterialCategory category;
}
