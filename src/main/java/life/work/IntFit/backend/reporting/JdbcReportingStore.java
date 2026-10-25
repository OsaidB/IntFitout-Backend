package life.work.IntFit.backend.reporting;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import static life.work.IntFit.backend.reporting.ReportingModel.*;

/** Explicit SQL schema in docs/sql; deliberately outside Hibernate automatic DDL. */
@Repository
public class JdbcReportingStore implements ReportingStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public JdbcReportingStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        tx = new TransactionTemplate(manager);
    }
    @Override public <T> T transaction(Supplier<T> action) { return tx.execute(s -> action.get()); }
    @Override public Worker lockWorker(long id) {
        return one(jdbc.query("SELECT id,name,whatsapp_reporting_eligible,whatsapp_number FROM team_members WHERE id=? FOR UPDATE",
                (r,n) -> new Worker(r.getLong(1), r.getString(2), r.getBoolean(3), r.getString(4)), id));
    }
    @Override public Worker inboundWorker(String recipient,String phone) {
        return one(jdbc.query("SELECT w.id,w.name,w.whatsapp_reporting_eligible,w.whatsapp_number FROM team_members w WHERE w.whatsapp_number=? AND EXISTS(SELECT 1 FROM whatsapp_report_requests r WHERE r.worker_id=w.id AND r.recipient=? AND r.phone_id=?)",WORKER,recipient,recipient,phone));
    }
    @Override public List<Worker> activeWorkers(LocalDate date) {
        return jdbc.query("SELECT w.id,w.name,w.whatsapp_reporting_eligible,w.whatsapp_number FROM team_members w WHERE EXISTS(SELECT 1 FROM work_assignments a WHERE a.team_member_id=w.id AND a.date BETWEEN ? AND ?) ORDER BY w.id",WORKER,date.minusDays(6),date);
    }
    @Override public void configureWorker(long id, boolean eligible, String number) {
        jdbc.update("UPDATE team_members SET whatsapp_reporting_eligible=?,whatsapp_number=? WHERE id=?", eligible, number, id);
    }
    @Override public boolean configureSite(long id, boolean selectable) {
        // Existence query avoids treating MySQL's unchanged-row count as a missing site.
        if (jdbc.queryForObject("SELECT COUNT(*) FROM master_worksites WHERE id=?", Integer.class, id) == 0) return false;
        jdbc.update("UPDATE master_worksites SET whatsapp_reporting_selectable=? WHERE id=?", selectable, id);
        return true;
    }
    @Override public List<Choice> selectableSites() {
        return jdbc.query("SELECT id,approved_name FROM master_worksites WHERE whatsapp_reporting_selectable=TRUE ORDER BY id", CHOICE);
    }
    @Override public boolean activeWorker(long id,LocalDate date) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM work_assignments WHERE team_member_id=? AND date BETWEEN ? AND ?)",Boolean.class,id,date.minusDays(6),date));
    }
    @Override public List<Choice> activeSites(LocalDate date) {
        return jdbc.query("SELECT m.id,COALESCE(NULLIF(TRIM(m.nickname),''),m.approved_name) FROM master_worksites m WHERE EXISTS(SELECT 1 FROM work_assignments a WHERE a.master_worksite_id=m.id AND a.date BETWEEN ? AND ?) ORDER BY m.id",CHOICE,date.minusDays(6),date);
    }
    @Override public List<Request> textCaptures(long worker) {
        return jdbc.query("SELECT * FROM whatsapp_report_requests WHERE worker_id=? AND step='OTHER_TEXT' AND submitted_at IS NULL ORDER BY created_at,id FOR UPDATE",REQUEST,worker);
    }
    @Override public List<Choice> coworkers(long except) {
        return jdbc.query("SELECT id,name FROM team_members WHERE whatsapp_reporting_eligible=TRUE AND id<>? ORDER BY id", CHOICE, except);
    }
    @Override public Request request(String id) {
        return one(jdbc.query("SELECT * FROM whatsapp_report_requests WHERE id=? FOR UPDATE", REQUEST, id));
    }
    @Override public Long requestWorkerId(String id) {
        return one(jdbc.queryForList("SELECT worker_id FROM whatsapp_report_requests WHERE id=?",Long.class,id));
    }
    @Override public Request request(long workerId, LocalDate date) {
        return one(jdbc.query("SELECT * FROM whatsapp_report_requests WHERE worker_id=? AND work_date=? FOR UPDATE", REQUEST, workerId, date));
    }
    @Override public void insertRequest(Request r) {
        jdbc.update("INSERT INTO whatsapp_report_requests(id,worker_id,recipient,phone_id,work_date,step,revision,created_at) VALUES(?,?,?,?,?,?,?,?)",
                r.id(), r.workerId(), r.recipient(), r.phoneId(), r.workDate(), r.step(), r.revision(), ts(r.createdAt()));
    }
    @Override public void saveRequest(Request r) {
        jdbc.update("UPDATE whatsapp_report_requests SET step=?,revision=?,current_site=?,outcome=?,last_inbound_at=?,submitted_at=? WHERE id=? AND submitted_at IS NULL",
                r.step(), r.revision(), r.currentSite(), r.outcome(), ts(r.lastInboundAt()), ts(r.submittedAt()), r.id());
    }
    @Override public List<Request> requests(int limit) {
        return jdbc.query("SELECT * FROM whatsapp_report_requests ORDER BY created_at DESC,id DESC LIMIT ?", REQUEST, limit);
    }
    @Override public List<Site> sites(String id) {
        return jdbc.query("SELECT s.*,m.approved_name AS business_name FROM whatsapp_report_sites s LEFT JOIN master_worksites m ON m.id=COALESCE(s.resolved_master_id,s.site_id) WHERE s.request_id=? ORDER BY s.entry_id",
                (r,n) -> new Site(r.getString("request_id"),r.getLong("entry_id"),r.getObject("site_id",Long.class),r.getString("label"),r.getString("original_description"),r.getObject("resolved_master_id",Long.class),r.getString("business_name"),r.getString("resolution_key"),instant(r,"resolved_at")), id);
    }
    @Override public void addSite(Site s) {
        jdbc.update("INSERT INTO whatsapp_report_sites(request_id,entry_id,site_id,label,original_description) VALUES(?,?,?,?,?)", s.requestId(),s.entryId(),s.siteId(),s.label(),s.originalDescription());
    }
    @Override public void resolveSite(String requestId,long entry,long master,String key,Instant at) {
        int changed=jdbc.update("UPDATE whatsapp_report_sites SET resolved_master_id=?,resolution_key=?,resolved_at=? WHERE request_id=? AND entry_id=? AND site_id IS NULL AND resolved_master_id IS NULL",master,key,ts(at),requestId,entry);
        if(changed!=1) throw new ReportingProblem(409,"Entry resolution conflicts with persisted state.");
    }
    @Override public List<Coworker> mentions(String id) {
        return jdbc.query("SELECT request_id,entry_id,worker_id,label FROM whatsapp_report_coworkers WHERE request_id=? ORDER BY entry_id,worker_id",
                (r,n) -> new Coworker(r.getString(1),r.getLong(2),r.getLong(3),r.getString(4)), id);
    }
    @Override public void addMention(Coworker c) {
        jdbc.update("INSERT INTO whatsapp_report_coworkers(request_id,entry_id,worker_id,label) VALUES(?,?,?,?)",
                c.requestId(),c.entryId(),c.workerId(),c.label());
    }
    @Override public void removeMention(String id,long site,long worker) {
        jdbc.update("DELETE FROM whatsapp_report_coworkers WHERE request_id=? AND entry_id=? AND worker_id=?",id,site,worker);
    }
    @Override public boolean hasInbox(String key) {
        return !jdbc.queryForList("SELECT event_key FROM whatsapp_report_inbox WHERE event_key=?",String.class,key).isEmpty();
    }
    @Override public void insertInbox(Inbox i) {
        jdbc.update("INSERT INTO whatsapp_report_inbox(event_key,message_id,context_id,kind,action,request_id,disposition,received_at,message_at,accepted_text) VALUES(?,?,?,?,?,?,?,?,?,?)",
                i.key(),i.messageId(),i.contextId(),i.kind(),i.action(),i.requestId(),i.disposition(),ts(i.receivedAt()),ts(i.messageAt()),i.acceptedText());
    }
    @Override public List<Inbox> inboxFor(String id) {
        return jdbc.query("SELECT * FROM whatsapp_report_inbox WHERE request_id=? ORDER BY received_at,event_key",
                (r,n) -> new Inbox(r.getString("event_key"),r.getString("message_id"),r.getString("context_id"),r.getString("kind"),
                        r.getString("action"),r.getString("request_id"),r.getString("disposition"),instant(r,"received_at"),instant(r,"message_at"),r.getString("accepted_text")),id);
    }
    @Override public void saveInbox(Inbox i) { jdbc.update("UPDATE whatsapp_report_inbox SET disposition=? WHERE event_key=?",i.disposition(),i.key()); }
    @Override public List<String> observedStatuses(String id) {
        return jdbc.queryForList("SELECT kind FROM whatsapp_report_inbox WHERE message_id=? AND kind LIKE 'STATUS_%'",String.class,id);
    }
    @Override public Outbound outbound(String id) {
        return one(jdbc.query("SELECT * FROM whatsapp_report_outbound WHERE id=? FOR UPDATE", OUTBOUND,id));
    }
    @Override public Outbound byMetaId(String id) {
        return one(jdbc.query("SELECT * FROM whatsapp_report_outbound WHERE meta_id=? FOR UPDATE", OUTBOUND,id));
    }
    @Override public List<Outbound> outboundFor(String id) {
        return jdbc.query("SELECT * FROM whatsapp_report_outbound WHERE request_id=? ORDER BY revision", OUTBOUND,id);
    }
    @Override public void insertOutbound(Outbound o) {
        // Legacy constructor support derives routing from the actual request, never from payload text.
        Request r=o.requestId()==null ? null : request(o.requestId());
        String recipient=o.recipient()!=null ? o.recipient() : r.recipient();
        String phone=o.phoneId()!=null ? o.phoneId() : r.phoneId();
        jdbc.update("INSERT INTO whatsapp_report_outbound(id,request_id,revision,kind,payload,state,created_at,recipient_type,recipient_identity,recipient,phone_id,run_id,failure_code) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
                o.id(),o.requestId(),o.revision(),o.kind(),o.payload(),o.state(),ts(o.createdAt()),o.recipientType(),o.recipientIdentity(),recipient,phone,o.runId(),o.failureCode());
    }
    @Override public void saveOutbound(Outbound o) {
        jdbc.update("UPDATE whatsapp_report_outbound SET state=?,meta_id=?,delivery=?,failure_code=? WHERE id=?",o.state(),o.metaId(),o.delivery(),o.failureCode(),o.id());
    }
    @Override public List<String> pendingIds(int limit) {
        return jdbc.queryForList("SELECT id FROM whatsapp_report_outbound WHERE state='PENDING' ORDER BY CASE WHEN recipient_type='MANAGEMENT' THEN 0 WHEN kind='TEMPLATE' THEN 1 ELSE 2 END,created_at,id LIMIT ?",String.class,limit);
    }
    @Override public Outbound peekOutbound(String id) {return one(jdbc.query("SELECT * FROM whatsapp_report_outbound WHERE id=?",OUTBOUND,id));}
    @Override public Outbound peekByMetaId(String id) {return one(jdbc.query("SELECT * FROM whatsapp_report_outbound WHERE meta_id=?",OUTBOUND,id));}
    @Override public List<Outbound> unboundOutbounds(String recipient,String phone) {
        return jdbc.query("SELECT * FROM whatsapp_report_outbound WHERE recipient=? AND phone_id=? AND meta_id IS NULL AND state IN ('SENDING','UNCERTAIN') ORDER BY created_at,id",OUTBOUND,recipient,phone);
    }
    @Override public DailyRun ensureRun(LocalDate date,String phone,Instant now,Instant cutoff,Instant expires,String owner,String father,String language) {
        // No-op duplicate update obtains an exclusive duplicate-row lock directly. INSERT IGNORE
        // takes shared duplicate locks whose later FOR UPDATE upgrades can deadlock concurrent ticks.
        jdbc.update("INSERT INTO whatsapp_daily_runs(id,phone_id,work_date,phase,created_at,cutoff_at,expires_at,owner_recipient,father_recipient,template_language) VALUES(?,?,?,'NEW',?,?,?,?,?,?) ON DUPLICATE KEY UPDATE id=whatsapp_daily_runs.id",java.util.UUID.randomUUID().toString(),phone,date,ts(now),ts(cutoff),ts(expires),owner,father,language);
        return one(jdbc.query("SELECT * FROM whatsapp_daily_runs WHERE phone_id=? AND work_date=? FOR UPDATE",RUN,phone,date));
    }
    @Override public DailyRun runByDate(LocalDate date,String phone) {return one(jdbc.query("SELECT * FROM whatsapp_daily_runs WHERE phone_id=? AND work_date=?",RUN,phone,date));}
    @Override public DailyRun lockRun(String id) {return one(jdbc.query("SELECT * FROM whatsapp_daily_runs WHERE id=? FOR UPDATE",RUN,id));}
    @Override public void saveRun(DailyRun r) {
        jdbc.update("UPDATE whatsapp_daily_runs SET phase=?,failure_code=?,summary_text=?,summary_parameters=? WHERE id=? AND summary_parameters IS NULL",r.phase(),r.failureCode(),r.summaryText(),r.summaryParameters(),r.id());
    }
    @Override public void addRunMember(DailyMember m) {
        jdbc.update("INSERT INTO whatsapp_daily_members(run_id,worker_id,configured,exclusion_code,request_id) VALUES(?,?,?,?,?)",m.runId(),m.workerId(),m.configured(),m.exclusionCode(),m.requestId());
    }
    @Override public List<DailyMember> runMembers(String id) {
        return jdbc.query("SELECT * FROM whatsapp_daily_members WHERE run_id=? ORDER BY worker_id",(r,n) -> new DailyMember(r.getString("run_id"),r.getLong("worker_id"),r.getBoolean("configured"),r.getString("exclusion_code"),r.getString("request_id")),id);
    }
    @Override public void linkOpeningRun(String request,String run) {
        jdbc.update("UPDATE whatsapp_report_outbound SET run_id=? WHERE request_id=? AND kind='TEMPLATE' AND run_id IS NULL",run,request);
    }
    @Override public List<Outbound> managementOutbound(String run) {return jdbc.query("SELECT * FROM whatsapp_report_outbound WHERE run_id=? AND recipient_type='MANAGEMENT' ORDER BY recipient_identity",OUTBOUND,run);}
    private static final RowMapper<Worker> WORKER=(r,n) -> new Worker(r.getLong(1),r.getString(2),r.getBoolean(3),r.getString(4));
    private static final RowMapper<DailyRun> RUN=(r,n) -> new DailyRun(r.getString("id"),r.getString("phone_id"),r.getDate("work_date").toLocalDate(),r.getString("phase"),instant(r,"created_at"),instant(r,"cutoff_at"),instant(r,"expires_at"),r.getString("owner_recipient"),r.getString("father_recipient"),r.getString("template_language"),r.getString("failure_code"),r.getString("summary_text"),r.getString("summary_parameters"));
    private static <T> T one(List<T> rows) { return rows.isEmpty() ? null : rows.get(0); }
    private static Timestamp ts(Instant i) { return i == null ? null : Timestamp.from(i); }
    private static Instant instant(java.sql.ResultSet r,String column) throws java.sql.SQLException {
        Timestamp t = r.getTimestamp(column); return t == null ? null : t.toInstant();
    }
    private static final RowMapper<Choice> CHOICE = (r,n) -> new Choice(r.getLong(1),r.getString(2));
    private static final RowMapper<Request> REQUEST = (r,n) -> new Request(r.getString("id"),r.getLong("worker_id"),
            r.getString("recipient"),r.getString("phone_id"),r.getDate("work_date").toLocalDate(),r.getString("step"),
            r.getLong("revision"),r.getObject("current_site", Long.class),r.getString("outcome"),
            instant(r,"created_at"),instant(r,"last_inbound_at"),instant(r,"submitted_at"));
    private static final RowMapper<Outbound> OUTBOUND = (r,n) -> new Outbound(r.getString("id"),r.getString("request_id"),
            r.getLong("revision"),r.getString("kind"),r.getString("payload"),r.getString("state"),
            r.getString("meta_id"),r.getString("delivery"),instant(r,"created_at"),r.getString("recipient_type"),r.getString("recipient_identity"),r.getString("recipient"),r.getString("phone_id"),r.getString("run_id"),r.getString("failure_code"));
}
