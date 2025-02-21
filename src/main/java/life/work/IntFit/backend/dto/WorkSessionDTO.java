package life.work.IntFit.backend.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class WorkSessionDTO {
    private Long id;
    private Long worksiteId;
    private String worksiteName;
    private String startTime;
    private String endTime;
    private String tasksPerformed;
    private String notes;
}
