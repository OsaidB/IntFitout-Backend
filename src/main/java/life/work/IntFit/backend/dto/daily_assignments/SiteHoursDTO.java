package life.work.IntFit.backend.dto.daily_assignments;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * Hours for one worksite. Clients should send {@code hours} as a decimal string
 * (e.g. "2.67"); JSON numbers are also parsed exactly from their text.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SiteHoursDTO {
    private Long masterWorksiteId;
    private BigDecimal hours;
}
