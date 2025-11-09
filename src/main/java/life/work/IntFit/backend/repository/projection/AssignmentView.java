// src/main/java/life/work/IntFit/backend/repository/projection/AssignmentView.java
package life.work.IntFit.backend.repository.projection;

import java.math.BigDecimal;
import java.time.LocalDate;

public interface AssignmentView {
    LocalDate getDate();
    Long getTeamMemberId();
    String getTeamMemberName();
    BigDecimal getDailyWage(); // from tm.dailyWage
}
