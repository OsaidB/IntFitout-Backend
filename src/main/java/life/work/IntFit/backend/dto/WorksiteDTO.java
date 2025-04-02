package life.work.IntFit.backend.dto;

import lombok.*;
import java.time.LocalDate;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorksiteDTO {
    private Long id;
    private String name;

    private String city;
    private String area;
    private String locationDetails;

    private String type;
    private String status;
    private String budget;

    private LocalDate startDate;
    private LocalDate deadline;
    private LocalDate endDate;

    private String description;
    private Integer progress;
    private boolean isArchived;

    private String notes;

    private List<WorksiteContactDTO> contacts; // Optional: only if you want full contact details returned
}
