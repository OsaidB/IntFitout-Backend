package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.*;
import life.work.IntFit.backend.dto.daily_assignments.*;
import life.work.IntFit.backend.model.entity.*;
import life.work.IntFit.backend.service.AssignmentConcurrency;
import life.work.IntFit.backend.service.allocation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import static life.work.IntFit.backend.reporting.ReportingModel.*;

/** Review is independent of current sending/activity/eligibility. No provider calls, wage or payroll writes. */
@Service
public class ReportReviewService {
    public record Decision(Long expectedRevision,String outcome,List<Long> masterWorksiteIds,List<Long> includedEntryIds,
                           BigDecimal overtimeHours,CategoryAllocationDTO regular,CategoryAllocationDTO overtime,
                           boolean removeExistingConfirmed) { }
    public record Row(long id,long masterWorksiteId,String name,@com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal regularHoursOverride,@com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal overtimeHoursOverride) { }
    public record WorkerDay(List<Row> assignments,String overtimeHours,boolean hasOvertimeRecord) { }
    public record Report(String id,long workerId,String workerName,LocalDate workDate,String step,String outcome,
                         Instant submittedAt,String reviewStatus,boolean deliveryProblem) { }
    public record Page(List<Report> items,int page,int size,long total) { }
    public record Entry(long entryId,Long siteId,String label,String originalDescription,Long resolvedMasterId,String businessName) { }
    public record Mention(long entryId,String name) { }
    public record Detail(Report report,List<Entry> entries,List<Mention> coworkers,WorkerDay existing,long revision,
                         JsonNode approval,String payrollStatus) { }
    public record Master(long id,String approvedName,String nickname) { }
    private final JdbcTemplate jdbc;
    private final ReportingStore store;
    private final AssignmentConcurrency concurrency;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final TransactionTemplate write,read;
    public ReportReviewService(JdbcTemplate jdbc,ReportingStore store,AssignmentConcurrency concurrency,
                               ObjectMapper mapper,Clock clock,PlatformTransactionManager manager) {
        this.jdbc=jdbc;this.store=store;this.concurrency=concurrency;this.mapper=mapper;this.clock=clock;
        write=new TransactionTemplate(manager);write.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    private static final String REPORT_SELECT="""
        SELECT r.id,r.worker_id,COALESCE(w.name,CONCAT('Deleted worker #',r.worker_id)) worker_name,
        r.work_date,r.step,r.outcome,r.submitted_at,
        CASE WHEN v.request_id IS NOT NULL THEN 'APPROVED' WHEN r.submitted_at IS NOT NULL THEN 'AWAITING_REVIEW' ELSE 'DRAFT' END review_status,
        EXISTS(SELECT 1 FROM whatsapp_report_outbound o WHERE o.request_id=r.id AND
            (o.state IN ('REJECTED','UNCERTAIN','CANCELLED','SENDING') OR o.delivery='failed')) delivery_problem
        FROM whatsapp_report_requests r LEFT JOIN team_members w ON w.id=r.worker_id
        LEFT JOIN whatsapp_report_reviews v ON v.request_id=r.id
        """;
    private final org.springframework.jdbc.core.RowMapper<Report> reportMapper=(s,n) -> new Report(s.getString("id"),s.getLong("worker_id"),s.getString("worker_name"),
            s.getObject("work_date",LocalDate.class),s.getString("step"),s.getString("outcome"),instant(s.getTimestamp("submitted_at")),s.getString("review_status"),s.getBoolean("delivery_problem"));
    public Page list(LocalDate date,String status,int page,int size) {
        if(!Set.of("AWAITING_REVIEW","APPROVED","DRAFT","ALL").contains(status)||page<0||page>100000||size<1||size>100)
            throw new ReportingProblem(400,"Invalid review filter or pagination.");
        return read.execute(t -> {
            String where=" WHERE 1=1";List<Object> args=new ArrayList<>();
            if(date!=null){where+=" AND r.work_date=?";args.add(date);}
            if(status.equals("APPROVED"))where+=" AND v.request_id IS NOT NULL";
            if(status.equals("AWAITING_REVIEW"))where+=" AND r.submitted_at IS NOT NULL AND v.request_id IS NULL";
            if(status.equals("DRAFT"))where+=" AND r.submitted_at IS NULL AND v.request_id IS NULL";
            long total=jdbc.queryForObject("SELECT COUNT(*) FROM whatsapp_report_requests r LEFT JOIN whatsapp_report_reviews v ON v.request_id=r.id"+where,Long.class,args.toArray());
            args.add(size);args.add((long)page*size);
            return new Page(jdbc.query(REPORT_SELECT+where+" ORDER BY r.work_date DESC,r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",reportMapper,args.toArray()),page,size,total);
        });
    }
    public List<Master> masters(String q,int page) {
        if(q==null||q.length()>100||page<0||page>100000)throw new ReportingProblem(400,"Invalid master search.");
        // LOCATE treats %, _ and backslash as ordinary characters, not SQL wildcards.
        return jdbc.query("SELECT id,approved_name,nickname FROM master_worksites WHERE LOCATE(?,approved_name)>0 OR LOCATE(?,COALESCE(nickname,''))>0 ORDER BY approved_name,id LIMIT 50 OFFSET ?",
                (s,n)->new Master(s.getLong(1),s.getString(2),s.getString(3)),q,q,(long)page*50);
    }
    public Detail detail(String id) { uuid(id);return read.execute(t -> detailNow(id)); }
    private Report report(String id) {
        var rows=jdbc.query(REPORT_SELECT+" WHERE r.id=?",reportMapper,id);
        if(rows.isEmpty())throw new ReportingProblem(404,"Report not found.");return rows.getFirst();
    }
    private Detail detailNow(String id) {
        Report r=report(id);
        long revision=concurrency.revision(r.workDate());
        var entries=store.sites(id).stream().map(s -> new Entry(s.entryId(),s.siteId(),s.label(),s.originalDescription(),s.resolvedMasterId(),s.businessName())).toList();
        var mentions=store.mentions(id).stream().map(c -> new Mention(c.entryId(),c.label())).toList();
        var statuses=jdbc.queryForList("SELECT status FROM payroll_week WHERE week_start<=? AND week_end>=? ORDER BY week_start",String.class,r.workDate(),r.workDate());
        return new Detail(r,entries,mentions,day(r.workerId(),r.workDate()),revision,approval(id),statuses.stream().filter(x -> "FINALIZED".equals(x)||"PAID".equals(x)).findFirst().orElse("EDITABLE"));
    }
    private WorkerDay day(long worker,LocalDate date) {
        var rows=jdbc.query("""
            SELECT a.id,a.master_worksite_id,COALESCE(m.approved_name,CONCAT('Deleted worksite #',a.master_worksite_id)),a.regular_hours_override,a.overtime_hours_override
            FROM work_assignments a LEFT JOIN master_worksites m ON m.id=a.master_worksite_id WHERE a.team_member_id=? AND a.date=? ORDER BY a.id
            """,(s,n)->new Row(s.getLong(1),s.getLong(2),s.getString(3),s.getBigDecimal(4),s.getBigDecimal(5)),worker,date);
        var overtime=jdbc.queryForList("SELECT overtime_hours FROM work_assignment_overtime WHERE team_member_id=? AND date=?",Double.class,worker,date);
        if(!overtime.isEmpty() && (overtime.getFirst()==null||!Double.isFinite(overtime.getFirst())||overtime.getFirst()<0))throw new ReportingProblem(409,"Stored overtime needs repair before review.");
        return new WorkerDay(rows,overtime.isEmpty()?"0":DecimalHours.toPlainString(DecimalHours.canonical(overtime.getFirst())),!overtime.isEmpty());
    }
    private JsonNode approval(String id) {
        var rows=jdbc.query("SELECT approved_at,reviewer_kind,decision_json,before_json,after_json,day_revision FROM whatsapp_report_reviews WHERE request_id=?",(s,n)-> {
            var a=mapper.createObjectNode();a.put("approvedAt",instant(s.getTimestamp(1)).toString());a.put("reviewerKind",s.getString(2));
            a.set("decision",json(s.getString(3)));a.set("before",json(s.getString(4)));a.set("after",json(s.getString(5)));a.put("revision",s.getLong(6));return (JsonNode)a;
        },id);return rows.isEmpty()?null:rows.getFirst();
    }
    public Detail approve(String id,Decision decision) {
        uuid(id);validate(decision);
        String canonical=canonical(decision),fingerprint=hash(canonical);
        // Immutable lookup only: no authorization or mutation uses this unlocked state.
        Report owner=report(id);
        return write.execute(t -> {
            long revision=concurrency.lockDay(owner.workDate());
            Worker lockedWorker=store.lockWorker(owner.workerId());
            Request request=store.request(id);
            var keys=jdbc.queryForList("SELECT fingerprint FROM whatsapp_report_reviews WHERE request_id=? FOR UPDATE",String.class,id);
            if(!keys.isEmpty()) {
                if(!fingerprint.equals(keys.getFirst()))throw new ReportingProblem(409,"Report was already approved with a different decision.");
                return detailNow(id);
            }
            if(lockedWorker==null)throw new ReportingProblem(409,"Worker no longer exists.");
            concurrency.requireRevision(decision.expectedRevision(),revision);
            if(request==null||request.submittedAt()==null||!"SUBMITTED".equals(request.step()))throw new ReportingProblem(409,"Only submitted reports can be approved.");
            concurrency.editable(request.workDate());
            var entries=store.sites(id);
            for(long entry:decision.includedEntryIds()) {
                Site s=entries.stream().filter(x -> x.entryId()==entry).findFirst().orElseThrow(()->new ReportingProblem(400,"Unknown included entry."));
                Long master=s.siteId()==null?s.resolvedMasterId():s.siteId();
                if(master==null)throw new ReportingProblem(409,"Resolve included Other entries before approval.");
                if(jdbc.queryForList("SELECT id FROM master_worksites WHERE id=? FOR SHARE",Long.class,master).isEmpty())throw new ReportingProblem(409,"An included worksite was deleted.");
            }
            WorkerDay before=day(request.workerId(),request.workDate());
            Set<Long> sites=new TreeSet<>(decision.masterWorksiteIds());
            Map<Long,String> names=new HashMap<>();
            for(long site:sites) {
                var found=jdbc.queryForList("SELECT approved_name FROM master_worksites WHERE id=? FOR SHARE",String.class,site);
                if(found.isEmpty())throw new ReportingProblem(409,"Selected master worksite no longer exists.");names.put(site,found.getFirst());
            }
            BigDecimal ot=decision.overtimeHours()==null?new BigDecimal(before.overtimeHours()):decision.overtimeHours();
            if("DID_NOT_WORK".equals(decision.outcome())) {
                if((!before.assignments().isEmpty()||before.hasOvertimeRecord())&&!decision.removeExistingConfirmed())throw new ReportingProblem(409,"Explicitly confirm removal of existing worker records.");
                if(before.hasOvertimeRecord()&&decision.overtimeHours()==null)throw new ReportingProblem(409,"Explicitly set overtime to zero to remove its record.");
                if(ot.signum()!=0)throw new ReportingProblem(409,"Did-not-work approval requires zero overtime.");
            }
            long worker=request.workerId();
            List<WorkAssignment> stored=new ArrayList<>();
            for(Row row:before.assignments()) {
                TeamMember member=new TeamMember();member.setId(worker);MasterWorksite master=new MasterWorksite();master.setId(row.masterWorksiteId());
                stored.add(WorkAssignment.builder().id(row.id()).teamMember(member).masterWorksite(master).date(request.workDate()).regularHoursOverride(row.regularHoursOverride()).overtimeHoursOverride(row.overtimeHoursOverride()).build());
            }
            var plan=AllocationPlanner.plan(new AllocationPlanner.Input(sites.isEmpty()?Map.of():Map.of(worker,sites),ot.signum()>0?Map.of(worker,ot):Map.of(),stored,
                List.of(new MemberAllocationDTO(worker,decision.regular(),decision.overtime())),Map.of(worker,owner.workerName()),names));
            boolean changed=false;Set<Long> retained=new HashSet<>();
            for(Row row:before.assignments()) {
                if(!sites.contains(row.masterWorksiteId())||!retained.add(row.masterWorksiteId())) {
                    jdbc.update("DELETE FROM work_assignments WHERE id=? AND team_member_id=? AND date=?",row.id(),worker,request.workDate());changed=true;
                } else {
                    BigDecimal regular=plan.regularFor(worker,row.masterWorksiteId()),overtime=plan.overtimeFor(worker,row.masterWorksiteId());
                    if(!equal(regular,row.regularHoursOverride())||!equal(overtime,row.overtimeHoursOverride())) {
                        jdbc.update("UPDATE work_assignments SET regular_hours_override=?,overtime_hours_override=? WHERE id=?",regular,overtime,row.id());changed=true;
                    }
                }
            }
            for(long site:sites)if(!retained.contains(site)) {
                jdbc.update("INSERT INTO work_assignments(team_member_id,master_worksite_id,date,regular_hours_override,overtime_hours_override) VALUES(?,?,?,?,?)",worker,site,request.workDate(),plan.regularFor(worker,site),plan.overtimeFor(worker,site));changed=true;
            }
            if(decision.overtimeHours()!=null && (ot.compareTo(new BigDecimal(before.overtimeHours()))!=0 || (ot.signum()==0&&before.hasOvertimeRecord()))) {
                if(ot.signum()==0)jdbc.update("DELETE FROM work_assignment_overtime WHERE team_member_id=? AND date=?",worker,request.workDate());
                else jdbc.update("INSERT INTO work_assignment_overtime(team_member_id,date,overtime_hours) VALUES(?,?,?) ON DUPLICATE KEY UPDATE overtime_hours=VALUES(overtime_hours)",worker,request.workDate(),DecimalHours.toDouble(ot));changed=true;
            }
            long next=changed?concurrency.bump(request.workDate()):revision;
            WorkerDay after=day(worker,request.workDate());
            jdbc.update("INSERT INTO whatsapp_report_reviews(request_id,approved_at,reviewer_kind,fingerprint,decision_json,before_json,after_json,day_revision) VALUES(?,?,?,?,?,?,?,?)",
                id,Timestamp.from(clock.instant()),"SHARED_REVIEW_KEY",fingerprint,canonical,encode(before),encode(after),next);
            return detailNow(id);
        });
    }
    private void validate(Decision d) {
        if(d==null||d.expectedRevision()==null||d.expectedRevision()<0||d.outcome()==null||!Set.of("WORKED","DID_NOT_WORK").contains(d.outcome())||d.masterWorksiteIds()==null||d.includedEntryIds()==null)
            throw new ReportingProblem(400,"Provide revision, outcome, masterWorksiteIds and includedEntryIds.");
        for(List<Long> ids:List.of(d.masterWorksiteIds(),d.includedEntryIds()))if(ids.size()>100||ids.stream().anyMatch(i->i==null||i<=0)||new HashSet<>(ids).size()!=ids.size())throw new ReportingProblem(400,"IDs must be distinct positive integers, at most 100.");
        if(d.outcome().equals("WORKED")==d.masterWorksiteIds().isEmpty() || (d.outcome().equals("DID_NOT_WORK")&&!d.includedEntryIds().isEmpty()))throw new ReportingProblem(400,"Worked requires sites; did-not-work requires no sites or included entries.");
        if(d.overtimeHours()!=null && (d.overtimeHours().signum()<0||!DecimalHours.isExactDouble(d.overtimeHours())||!DecimalHours.fitsStorage(d.overtimeHours())))throw new ReportingProblem(400,"Overtime must be a supported non-negative exact decimal.");
    }
    private String canonical(Decision d) {
        // Typed, bounded, stable ordering. Equivalent reordering of site/entry lists is idempotent.
        var node=mapper.valueToTree(d);((com.fasterxml.jackson.databind.node.ObjectNode)node).set("masterWorksiteIds",mapper.valueToTree(new TreeSet<>(d.masterWorksiteIds())));
        ((com.fasterxml.jackson.databind.node.ObjectNode)node).set("includedEntryIds",mapper.valueToTree(new TreeSet<>(d.includedEntryIds())));return encode(node);
    }
    private String encode(Object o) {try{return mapper.writeValueAsString(o);}catch(Exception e){throw new IllegalStateException("Review serialization failed");}}
    private JsonNode json(String s) {try{return mapper.readTree(s);}catch(Exception e){throw new IllegalStateException("Stored review is invalid");}}
    private static String hash(String s) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static boolean equal(BigDecimal a,BigDecimal b){return a==null?b==null:b!=null&&a.compareTo(b)==0;}
    private static Instant instant(Timestamp t){return t==null?null:t.toInstant();}
    static void uuid(String id){try{if(!UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException();}catch(Exception e){throw new ReportingProblem(400,"Canonical report UUID required.");}}
}
