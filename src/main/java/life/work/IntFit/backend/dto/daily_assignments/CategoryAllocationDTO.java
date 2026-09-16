package life.work.IntFit.backend.dto.daily_assignments;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * {@code mode} is "AUTO" (reset to the equal split; {@code hours} must be empty)
 * or "CUSTOM" ({@code hours} must cover every worksite the member is assigned to
 * that day and sum exactly to the category's daily total).
 * Kept as a string so an invalid value produces an actionable error.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CategoryAllocationDTO {
    private String mode;
    private List<SiteHoursDTO> hours;
}
