// src/main/java/life/work/IntFit/backend/repository/projection/SiteCountView.java
package life.work.IntFit.backend.repository.projection;

import java.time.LocalDate;

public interface SiteCountView {
    LocalDate getDate();
    Long getTeamMemberId();
    Long getSiteCount();
}
