package life.work.IntFit.backend.dto;

//import life.work.IntFit.backend.model.entity.MeasurementTask;
import life.work.IntFit.backend.model.entity.MeasurementTask.CalculationType;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MeasurementTaskDTO {

    private Long id;

    private String taskType;

    private double length;

    private double width;

    private double height;

    private String unit; // e.g., "m", "cm", etc.
    private Double unitCost;

    private Double totalCost;

    private Long roomId;
    private CalculationType calculationType; // ✅ MISSING FIELD

}
