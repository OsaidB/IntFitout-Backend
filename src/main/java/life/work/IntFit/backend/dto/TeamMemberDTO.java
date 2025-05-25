package life.work.IntFit.backend.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class TeamMemberDTO {
    private Long id;
    private String name;
    private String role;
    private String experience;
    private String contact;

    private Double dailyWage; // New field for construction worker's pay per day
}
