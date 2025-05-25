package life.work.IntFit.backend.dto;

import lombok.*;

import java.time.LocalDate;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class WorkAssignmentDTO {
    private Long id;
    private Long teamMemberId;
    private Long worksiteId;
    private String teamMemberName;
    private String worksiteName;
    private LocalDate date;
}
