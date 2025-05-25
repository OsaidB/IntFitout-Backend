package life.work.IntFit.backend.dto;

import lombok.*;

import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class DailyWorkAssignmentDTO {
    private LocalDate date;
    private List<SimpleAssignmentDTO> assignments;
}
