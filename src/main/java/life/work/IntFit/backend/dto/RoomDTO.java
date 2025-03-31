package life.work.IntFit.backend.dto;

import lombok.*;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RoomDTO {

    private Long id;

    private String name;

    private Long worksiteId;

    private List<MeasurementTaskDTO> tasks;

    private double totalCost;
}
