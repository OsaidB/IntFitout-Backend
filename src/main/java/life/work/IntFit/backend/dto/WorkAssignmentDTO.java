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
    private Long masterWorksiteId;
    private String teamMemberName;
    private String masterWorksiteName;
    private LocalDate date;

    private Double teamMemberDailyWage;

}
