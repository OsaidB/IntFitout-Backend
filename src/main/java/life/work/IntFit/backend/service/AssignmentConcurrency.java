package life.work.IntFit.backend.service;

import java.time.LocalDate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;

/** Global order: payroll-week guard -> assignment-day guard -> reporting worker -> request.
 * Guard rows cover empty days and weeks that have never had payroll generated.
 * All writers use READ_COMMITTED so a wait never leaves an obsolete JPA snapshot. */
@Service
public class AssignmentConcurrency {
    private final JdbcTemplate jdbc;
    public AssignmentConcurrency(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private void transactionRequired() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new IllegalStateException("Assignment lock requires a transaction");
    }
    public void lockWeek(LocalDate start) {
        transactionRequired();
        jdbc.update("INSERT INTO assignment_week_guards(week_start) VALUES(?) ON DUPLICATE KEY UPDATE week_start=assignment_week_guards.week_start",start);
        jdbc.queryForObject("SELECT week_start FROM assignment_week_guards WHERE week_start=? FOR UPDATE",LocalDate.class,start);
    }
    public void lockPayroll(long id) {
        LocalDate start=jdbc.queryForObject("SELECT week_start FROM payroll_week WHERE id=?",LocalDate.class,id);
        lockWeek(start);
    }
    public long lockDay(LocalDate date) {
        lockWeek(PayrollService.satThu(date)[0]);
        jdbc.update("INSERT INTO assignment_day_revisions(work_date,revision) VALUES(?,0) ON DUPLICATE KEY UPDATE work_date=assignment_day_revisions.work_date",date);
        return jdbc.queryForObject("SELECT revision FROM assignment_day_revisions WHERE work_date=? FOR UPDATE",Long.class,date);
    }
    /** Call first in a repeatable-read read-only transaction, before reading day contents. */
    public long revision(LocalDate date) {
        var rows=jdbc.queryForList("SELECT revision FROM assignment_day_revisions WHERE work_date=?",Long.class,date);
        return rows.isEmpty()?0:rows.getFirst();
    }
    public void requireRevision(Long expected,long actual) {
        if(expected==null || expected<0 || expected!=actual)
            throw new ResponseStatusException(CONFLICT,"Assignment day changed or revision is missing. Refresh and review your unsaved proposal before saving.");
    }
    public void editable(LocalDate date) {
        // Current locking read, including any overlapping legacy payroll ranges.
        var statuses=jdbc.queryForList("SELECT status FROM payroll_week WHERE week_start<=? AND week_end>=? FOR UPDATE",String.class,date,date);
        if(statuses.stream().anyMatch(s -> "FINALIZED".equals(s)||"PAID".equals(s)))
            throw new ResponseStatusException(CONFLICT,"Assignments are frozen by finalized or paid payroll.");
    }
    public long bump(LocalDate date) {
        jdbc.update("UPDATE assignment_day_revisions SET revision=revision+1 WHERE work_date=?",date);
        return revision(date);
    }
}
