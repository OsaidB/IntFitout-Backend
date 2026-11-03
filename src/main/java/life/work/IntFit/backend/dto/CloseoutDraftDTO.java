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

    /** The optimistic-lock counter. Exposed for diagnostics; `revision` is authoritative. */
    private Long version;

    /**
     * The revision token the client must send back on the next save, as "<draftId>:<version>".
     *
     * It names the draft row as well as its counter, because the counter alone is not
     * enough: deleting a draft and creating a new one starts a fresh row back at 0, so a
     * device still holding the deleted draft's "0" would otherwise look current and
     * overwrite the replacement. Comparing the row identity closes that.
     */
    private String revision;
}
