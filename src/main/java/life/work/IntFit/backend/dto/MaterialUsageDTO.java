package life.work.IntFit.backend.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class MaterialUsageDTO {
    private Long id;
    private String date;
    private Long worksiteId;
    private String worksiteName;
    private String materialName;
    private Double quantity;
    private String unit;
    private Double cost;
    private String notes;
}
