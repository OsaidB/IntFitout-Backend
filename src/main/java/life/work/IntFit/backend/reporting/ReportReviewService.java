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
                           boolean removeExistingConfirmed,List<CoworkerDecision> coworkers) {
        public List<CoworkerDecision> coworkers() { return coworkers==null?List.of():coworkers; }
        /** Reporter-only decision: unchanged shape for every existing caller and stored payload. */
        public Decision(Long expectedRevision,String outcome,List<Long> masterWorksiteIds,List<Long> includedEntryIds,
                        BigDecimal overtimeHours,CategoryAllocationDTO regular,CategoryAllocationDTO overtime,
                        boolean removeExistingConfirmed) {
            this(expectedRevision,outcome,masterWorksiteIds,includedEntryIds,overtimeHours,regular,overtime,removeExistingConfirmed,null);
        }
    }
    /** An explicitly selected coworker. entryIds are the reporter's entries whose worksites they are added to.
     *  Their own overtime/allocations are independent; null preserves what is already stored for them. */
    public record CoworkerDecision(Long workerId,List<Long> entryIds,BigDecimal overtimeHours,
                                   CategoryAllocationDTO regular,CategoryAllocationDTO overtime) { }
    public record Row(long id,long masterWorksiteId,String name,@com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal regularHoursOverride,@com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class) BigDecimal overtimeHoursOverride) { }
    public record WorkerDay(List<Row> assignments,String overtimeHours,boolean hasOvertimeRecord) { }
    public record Report(String id,long workerId,String workerName,LocalDate workDate,String step,String outcome,
                         Instant submittedAt,String reviewStatus,boolean deliveryProblem) { }
    public record Page(List<Report> items,int page,int size,long total) { }
    public record Entry(long entryId,Long siteId,String label,String originalDescription,Long resolvedMasterId,String businessName) { }
    public record Mention(long entryId,long workerId,String name) { }
    /** Read-only preview of one mentioned coworker's current day. Never creates records. */
    public record CoworkerDay(long workerId,String name,WorkerDay existing) { }
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
    /** Preview only. Restricted to workers this report actually mentions, so it is not a generic day peek. */
    public CoworkerDay coworkerDay(String id,long workerId) {
        uuid(id);
        return read.execute(t -> {
            Report r=report(id);
            var mention=store.mentions(id).stream().filter(c -> c.workerId()==workerId).findFirst()
                    .orElseThrow(()->new ReportingProblem(404,"That worker is not mentioned in this report."));
            if(workerId==r.workerId())throw new ReportingProblem(400,"The reporting worker is not a coworker selection.");
            var named=jdbc.queryForList("SELECT name FROM team_members WHERE id=?",String.class,workerId);
            return new CoworkerDay(workerId,named.isEmpty()?mention.label():named.getFirst(),day(workerId,r.workDate()));
        });
    }
    private Report report(String id) {
        var rows=jdbc.query(REPORT_SELECT+" WHERE r.id=?",reportMapper,id);
        if(rows.isEmpty())throw new ReportingProblem(404,"Report not found.");return rows.getFirst();
    }
    private Detail detailNow(String id) {
        Report r=report(id);
        long revision=concurrency.revision(r.workDate());
        var entries=store.sites(id).stream().map(s -> new Entry(s.entryId(),s.siteId(),s.label(),s.originalDescription(),s.resolvedMasterId(),s.businessName())).toList();
        var mentions=store.mentions(id).stream().map(c -> new Mention(c.entryId(),c.workerId(),c.label())).toList();
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
        var rows=jdbc.query("SELECT approved_at,reviewer_kind,decision_json,before_json,after_json,day_revision,coworkers_json FROM whatsapp_report_reviews WHERE request_id=?",(s,n)-> {
            var a=mapper.createObjectNode();a.put("approvedAt",instant(s.getTimestamp(1)).toString());a.put("reviewerKind",s.getString(2));
            a.set("decision",json(s.getString(3)));a.set("before",json(s.getString(4)));a.set("after",json(s.getString(5)));a.put("revision",s.getLong(6));
            // Null for reporter-only approvals, including every review stored before coworkers existed.
            String coworkers=s.getString(7);a.set("coworkers",coworkers==null?mapper.createArrayNode():json(coworkers));
            return (JsonNode)a;
        },id);return rows.isEmpty()?null:rows.getFirst();
    }
    public Detail approve(String id,Decision decision) {
        uuid(id);validate(decision);
        String canonical=canonical(decision),fingerprint=hash(canonical);
        // Immutable lookup only: no authorization or mutation uses this unlocked state.
        Report owner=report(id);
        for(CoworkerDecision c:decision.coworkers())
            if(c.workerId()==owner.workerId())throw new ReportingProblem(400,"The reporting worker cannot also be selected as a coworker.");
        return write.execute(t -> {
            long revision=concurrency.lockDay(owner.workDate());
            // Every affected worker is locked in ascending id order, so two approvals touching
            // overlapping people can never deadlock against each other.
            Set<Long> affected=new TreeSet<>();affected.add(owner.workerId());
            for(CoworkerDecision c:decision.coworkers())affected.add(c.workerId());
            Map<Long,Worker> locked=new LinkedHashMap<>();
            for(long w:affected)locked.put(w,store.lockWorker(w));
            Worker lockedWorker=locked.get(owner.workerId());
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
            Map<Long,Long> entryMaster=new HashMap<>();
            for(long entry:decision.includedEntryIds()) {
                Site s=entries.stream().filter(x -> x.entryId()==entry).findFirst().orElseThrow(()->new ReportingProblem(400,"Unknown included entry."));
                Long master=s.siteId()==null?s.resolvedMasterId():s.siteId();
                if(master==null)throw new ReportingProblem(409,"Resolve included Other entries before approval.");
                if(jdbc.queryForList("SELECT id FROM master_worksites WHERE id=? FOR SHARE",Long.class,master).isEmpty())throw new ReportingProblem(409,"An included worksite was deleted.");
                entryMaster.put(entry,master);
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

            // ---- selected coworkers, checked against this report's own stored mentions ----
            var mentions=store.mentions(id);
            Map<Long,Set<Long>> coworkerAdds=new TreeMap<>();
            for(CoworkerDecision c:decision.coworkers()) {
                long cw=c.workerId();
                if(locked.get(cw)==null)throw new ReportingProblem(409,"A selected coworker no longer exists.");
                Set<Long> add=new TreeSet<>();
                for(long entry:c.entryIds()) {
                    if(mentions.stream().noneMatch(m -> m.entryId()==entry && m.workerId()==cw))
                        throw new ReportingProblem(409,"That coworker was not mentioned at that worksite in this report.");
                    Long master=entryMaster.get(entry);
                    if(master==null)throw new ReportingProblem(409,"Coworker entries must be included and resolved before approval.");
                    if(!sites.contains(master))throw new ReportingProblem(409,"Keep a selected coworker worksite among the approved worksites.");
                    add.add(master);
                }
                coworkerAdds.put(cw,add);
            }

            // ---- one plan over every affected person; coworkers keep their own day and hours ----
            Map<Long,WorkerDay> cwBefore=new LinkedHashMap<>();
            Map<Long,Set<Long>> targets=new LinkedHashMap<>();
            Map<Long,BigDecimal> newOvertime=new LinkedHashMap<>();
            Map<Long,String> memberNames=new HashMap<>();
            List<WorkAssignment> stored=new ArrayList<>();
            List<MemberAllocationDTO> requests=new ArrayList<>();

            targets.put(worker,sites);
            if(ot.signum()>0)newOvertime.put(worker,ot);
            memberNames.put(worker,owner.workerName());
            requests.add(new MemberAllocationDTO(worker,decision.regular(),decision.overtime()));
            collect(stored,worker,request.workDate(),before);

            for(var e:coworkerAdds.entrySet()) {
                long cw=e.getKey();
                WorkerDay b=day(cw,request.workDate());cwBefore.put(cw,b);
                // Existing worksites are preserved; the selected ones are added to that day.
                Set<Long> target=new TreeSet<>();
                for(Row r:b.assignments())target.add(r.masterWorksiteId());
                target.addAll(e.getValue());
                targets.put(cw,target);
                CoworkerDecision cd=selection(decision,cw);
                BigDecimal cwOt=cd.overtimeHours()==null?new BigDecimal(b.overtimeHours()):cd.overtimeHours();
                if(cwOt.signum()>0)newOvertime.put(cw,cwOt);
                memberNames.put(cw,locked.get(cw).name());
                requests.add(new MemberAllocationDTO(cw,cd.regular(),cd.overtime()));
                collect(stored,cw,request.workDate(),b);
                for(long site:target)if(!names.containsKey(site)) {
                    var found=jdbc.queryForList("SELECT approved_name FROM master_worksites WHERE id=? FOR SHARE",String.class,site);
                    names.put(site,found.isEmpty()?("Deleted worksite #"+site):found.getFirst());
                }
            }
            Map<Long,Set<Long>> newSites=new LinkedHashMap<>();
            targets.forEach((m,set) -> { if(!set.isEmpty())newSites.put(m,set); });
            var plan=AllocationPlanner.plan(new AllocationPlanner.Input(newSites,newOvertime,stored,requests,memberNames,names));

            boolean changed=apply(worker,request.workDate(),before,sites,plan);
            if(decision.overtimeHours()!=null && (ot.compareTo(new BigDecimal(before.overtimeHours()))!=0 || (ot.signum()==0&&before.hasOvertimeRecord())))
                changed=overtime(worker,request.workDate(),ot)||changed;
            for(var e:coworkerAdds.entrySet()) {
                long cw=e.getKey();WorkerDay b=cwBefore.get(cw);CoworkerDecision cd=selection(decision,cw);
                changed=apply(cw,request.workDate(),b,targets.get(cw),plan)||changed;
                if(cd.overtimeHours()!=null && (cd.overtimeHours().compareTo(new BigDecimal(b.overtimeHours()))!=0 || (cd.overtimeHours().signum()==0&&b.hasOvertimeRecord())))
                    changed=overtime(cw,request.workDate(),cd.overtimeHours())||changed;
            }

            long next=changed?concurrency.bump(request.workDate()):revision;
            WorkerDay after=day(worker,request.workDate());
            var coworkerEvidence=mapper.createArrayNode();
            for(var e:coworkerAdds.entrySet()) {
                long cw=e.getKey();var node=mapper.createObjectNode();
                node.put("workerId",cw);node.put("name",memberNames.get(cw));
                node.set("entryIds",mapper.valueToTree(new TreeSet<>(selection(decision,cw).entryIds())));
                node.set("addedMasterWorksiteIds",mapper.valueToTree(e.getValue()));
                node.set("before",mapper.valueToTree(cwBefore.get(cw)));
                node.set("after",mapper.valueToTree(day(cw,request.workDate())));
                coworkerEvidence.add(node);
            }
            jdbc.update("INSERT INTO whatsapp_report_reviews(request_id,approved_at,reviewer_kind,fingerprint,decision_json,before_json,after_json,day_revision,coworkers_json) VALUES(?,?,?,?,?,?,?,?,?)",
                id,Timestamp.from(clock.instant()),"SHARED_REVIEW_KEY",fingerprint,canonical,encode(before),encode(after),next,
                coworkerEvidence.isEmpty()?null:encode(coworkerEvidence));
            return detailNow(id);
        });
    }
    private static CoworkerDecision selection(Decision d,long worker) {
        return d.coworkers().stream().filter(x -> x.workerId()==worker).findFirst().orElseThrow();
    }
    /** Existing rows for one member, as the planner stored baseline. */
    private static void collect(List<WorkAssignment> into,long worker,LocalDate date,WorkerDay day) {
        for(Row row:day.assignments()) {
            TeamMember member=new TeamMember();member.setId(worker);MasterWorksite master=new MasterWorksite();master.setId(row.masterWorksiteId());
            into.add(WorkAssignment.builder().id(row.id()).teamMember(member).masterWorksite(master).date(date)
                    .regularHoursOverride(row.regularHoursOverride()).overtimeHoursOverride(row.overtimeHoursOverride()).build());
        }
    }
    /** Row-level delta for one member; never the destructive whole-day save. A target that keeps every
     *  existing worksite (a coworker) therefore deletes nothing; only duplicate rows collapse. */
    private boolean apply(long worker,LocalDate date,WorkerDay before,Set<Long> target,AllocationPlanner.Plan plan) {
        boolean changed=false;Set<Long> retained=new HashSet<>();
        for(Row row:before.assignments()) {
            if(!target.contains(row.masterWorksiteId())||!retained.add(row.masterWorksiteId())) {
                jdbc.update("DELETE FROM work_assignments WHERE id=? AND team_member_id=? AND date=?",row.id(),worker,date);changed=true;
            } else {
                BigDecimal regular=plan.regularFor(worker,row.masterWorksiteId()),overtime=plan.overtimeFor(worker,row.masterWorksiteId());
                if(!equal(regular,row.regularHoursOverride())||!equal(overtime,row.overtimeHoursOverride())) {
                    jdbc.update("UPDATE work_assignments SET regular_hours_override=?,overtime_hours_override=? WHERE id=?",regular,overtime,row.id());changed=true;
                }
            }
        }
        for(long site:target)if(!retained.contains(site)) {
            jdbc.update("INSERT INTO work_assignments(team_member_id,master_worksite_id,date,regular_hours_override,overtime_hours_override) VALUES(?,?,?,?,?)",
                worker,site,date,plan.regularFor(worker,site),plan.overtimeFor(worker,site));changed=true;
        }
        return changed;
    }
    private boolean overtime(long worker,LocalDate date,BigDecimal hours) {
        if(hours.signum()==0)jdbc.update("DELETE FROM work_assignment_overtime WHERE team_member_id=? AND date=?",worker,date);
        else jdbc.update("INSERT INTO work_assignment_overtime(team_member_id,date,overtime_hours) VALUES(?,?,?) ON DUPLICATE KEY UPDATE overtime_hours=VALUES(overtime_hours)",worker,date,DecimalHours.toDouble(hours));
        return true;
    }
    private void validate(Decision d) {
        if(d==null||d.expectedRevision()==null||d.expectedRevision()<0||d.outcome()==null||!Set.of("WORKED","DID_NOT_WORK").contains(d.outcome())||d.masterWorksiteIds()==null||d.includedEntryIds()==null)
            throw new ReportingProblem(400,"Provide revision, outcome, masterWorksiteIds and includedEntryIds.");
        for(List<Long> ids:List.of(d.masterWorksiteIds(),d.includedEntryIds()))if(ids.size()>100||ids.stream().anyMatch(i->i==null||i<=0)||new HashSet<>(ids).size()!=ids.size())throw new ReportingProblem(400,"IDs must be distinct positive integers, at most 100.");
        if(d.outcome().equals("WORKED")==d.masterWorksiteIds().isEmpty() || (d.outcome().equals("DID_NOT_WORK")&&!d.includedEntryIds().isEmpty()))throw new ReportingProblem(400,"Worked requires sites; did-not-work requires no sites or included entries.");
        if(d.overtimeHours()!=null && (d.overtimeHours().signum()<0||!DecimalHours.isExactDouble(d.overtimeHours())||!DecimalHours.fitsStorage(d.overtimeHours())))throw new ReportingProblem(400,"Overtime must be a supported non-negative exact decimal.");
        var selections=d.coworkers();
        if(selections.size()>50)throw new ReportingProblem(400,"At most 50 coworkers can be selected.");
        if("DID_NOT_WORK".equals(d.outcome())&&!selections.isEmpty())throw new ReportingProblem(400,"Did-not-work approval cannot assign coworkers.");
        Set<Long> seen=new HashSet<>();
        for(CoworkerDecision c:selections) {
            if(c==null||c.workerId()==null||c.workerId()<=0||c.entryIds()==null||c.entryIds().isEmpty())throw new ReportingProblem(400,"Each coworker needs a positive worker id and at least one entry.");
            if(!seen.add(c.workerId()))throw new ReportingProblem(400,"List each selected coworker once, with all of their entries.");
            if(c.entryIds().size()>100||c.entryIds().stream().anyMatch(i->i==null||i<=0)||new HashSet<>(c.entryIds()).size()!=c.entryIds().size())throw new ReportingProblem(400,"Coworker entry ids must be distinct positive integers, at most 100.");
            if(!d.includedEntryIds().containsAll(c.entryIds()))throw new ReportingProblem(400,"Coworker entries must be included in the approved report entries.");
            if(c.overtimeHours()!=null && (c.overtimeHours().signum()<0||!DecimalHours.isExactDouble(c.overtimeHours())||!DecimalHours.fitsStorage(c.overtimeHours())))throw new ReportingProblem(400,"Coworker overtime must be a supported non-negative exact decimal.");
        }
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
