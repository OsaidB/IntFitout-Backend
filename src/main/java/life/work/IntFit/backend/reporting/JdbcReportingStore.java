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
    @Override public List<Choice> coworkers(long except) {
        return jdbc.query("SELECT id,name FROM team_members WHERE whatsapp_reporting_eligible=TRUE AND id<>? ORDER BY id", CHOICE, except);
    }
    @Override public Request request(String id) {
        return one(jdbc.query("SELECT * FROM whatsapp_report_requests WHERE id=? FOR UPDATE", REQUEST, id));
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
        return jdbc.query("SELECT request_id,site_id,label FROM whatsapp_report_sites WHERE request_id=? ORDER BY site_id",
                (r,n) -> new Site(r.getString(1),r.getLong(2),r.getString(3)), id);
    }
    @Override public void addSite(Site s) {
        jdbc.update("INSERT INTO whatsapp_report_sites(request_id,site_id,label) VALUES(?,?,?)", s.requestId(),s.siteId(),s.label());
    }
    @Override public List<Coworker> mentions(String id) {
        return jdbc.query("SELECT request_id,site_id,worker_id,label FROM whatsapp_report_coworkers WHERE request_id=? ORDER BY site_id,worker_id",
                (r,n) -> new Coworker(r.getString(1),r.getLong(2),r.getLong(3),r.getString(4)), id);
    }
    @Override public void addMention(Coworker c) {
        jdbc.update("INSERT INTO whatsapp_report_coworkers(request_id,site_id,worker_id,label) VALUES(?,?,?,?)",
                c.requestId(),c.siteId(),c.workerId(),c.label());
    }
    @Override public void removeMention(String id,long site,long worker) {
        jdbc.update("DELETE FROM whatsapp_report_coworkers WHERE request_id=? AND site_id=? AND worker_id=?",id,site,worker);
    }
    @Override public boolean hasInbox(String key) {
        return !jdbc.queryForList("SELECT event_key FROM whatsapp_report_inbox WHERE event_key=?",String.class,key).isEmpty();
    }
    @Override public void insertInbox(Inbox i) {
        jdbc.update("INSERT INTO whatsapp_report_inbox(event_key,message_id,context_id,kind,action,request_id,disposition,received_at,message_at) VALUES(?,?,?,?,?,?,?,?,?)",
                i.key(),i.messageId(),i.contextId(),i.kind(),i.action(),i.requestId(),i.disposition(),ts(i.receivedAt()),ts(i.messageAt()));
    }
    @Override public List<Inbox> inboxFor(String id) {
        return jdbc.query("SELECT * FROM whatsapp_report_inbox WHERE request_id=? ORDER BY received_at,event_key",
                (r,n) -> new Inbox(r.getString("event_key"),r.getString("message_id"),r.getString("context_id"),r.getString("kind"),
                        r.getString("action"),r.getString("request_id"),r.getString("disposition"),instant(r,"received_at"),instant(r,"message_at")),id);
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
        jdbc.update("INSERT INTO whatsapp_report_outbound(id,request_id,revision,kind,payload,state,created_at) VALUES(?,?,?,?,?,?,?)",
                o.id(),o.requestId(),o.revision(),o.kind(),o.payload(),o.state(),ts(o.createdAt()));
    }
    @Override public void saveOutbound(Outbound o) {
        jdbc.update("UPDATE whatsapp_report_outbound SET state=?,meta_id=?,delivery=? WHERE id=?",o.state(),o.metaId(),o.delivery(),o.id());
    }
    @Override public List<String> pendingIds(int limit) {
        return jdbc.queryForList("SELECT id FROM whatsapp_report_outbound WHERE state='PENDING' ORDER BY created_at,id LIMIT ?",String.class,limit);
    }
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
            r.getString("meta_id"),r.getString("delivery"),instant(r,"created_at"));
}
