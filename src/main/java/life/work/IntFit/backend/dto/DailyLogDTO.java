package life.work.IntFit.backend.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class DailyLogDTO {
    private Long id;
    private String date;
    private Long employeeId;
    private String employeeName;
    private List<WorkSessionDTO> workSessions;
}
