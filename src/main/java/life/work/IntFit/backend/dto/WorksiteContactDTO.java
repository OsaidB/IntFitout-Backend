package life.work.IntFit.backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorksiteContactDTO {
    private Long id;

    private Long worksiteId;
    private Long contactId;

    private String note;

    // Optional: to return contact details without extra queries
    private ContactDTO contact;
}
