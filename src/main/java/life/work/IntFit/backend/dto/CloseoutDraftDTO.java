package life.work.IntFit.backend.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.*;

import java.time.Instant;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CloseoutDraftDTO {
    private Long id;
    private Long masterWorksiteId;
    private JsonNode draft;
    private Instant updatedAt;
}
