package life.work.IntFit.backend.dto;

import lombok.*;
import life.work.IntFit.backend.model.entity.Contact.ContactType;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ContactDTO {
    private Long id;
    private String name;
    private String phone;
    private ContactType type;
}
