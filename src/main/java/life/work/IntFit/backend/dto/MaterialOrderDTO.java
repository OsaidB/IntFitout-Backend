package life.work.IntFit.backend.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class MaterialOrderDTO {
    private Long id;
    private String date;
    private Long worksiteId;
    private String worksiteName;
    private List<MaterialOrderItemDTO> items;
    private String notes;
}
