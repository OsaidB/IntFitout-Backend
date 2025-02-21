package life.work.IntFit.backend.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class MaterialOrderItemDTO {
    private Long id;
    private Long materialId;
    private String materialName;
    private Double quantity;
    private Double cost;
}
