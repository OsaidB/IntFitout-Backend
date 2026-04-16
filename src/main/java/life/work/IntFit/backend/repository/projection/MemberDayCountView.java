package life.work.IntFit.backend.repository.projection;

import java.time.LocalDate;

public interface MemberDayCountView {
    LocalDate getDate();
    Long getTeamMemberId();
    Long getSiteCount();
}
