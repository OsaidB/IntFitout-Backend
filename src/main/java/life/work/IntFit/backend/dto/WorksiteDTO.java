package life.work.IntFit.backend.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class WorksiteDTO {
    private Long id;
    private String name;
    private String location;
    private String status;
    private String manager;
    private String budget;
    private String deadline;
}
