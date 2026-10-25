package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static life.work.IntFit.backend.reporting.ReportingModel.*;

/** Durable business-date orchestration. Never performs HTTP inside a transaction. */
@Service
public class DailyReportingService {
    static final ZoneId ZONE=ZoneId.of("Asia/Hebron");
    static final String TEMPLATE="daily_worker_dispatch_summary_ar";
    static final String BODY="ملخص طلبات العمل — {{1}}\nحالة الجولة: {{2}}\nالعمال النشطون: {{3}}\nالمشاركون بأرقام صالحة: {{4}}\nطلبات ضمن نطاق الإرسال: {{5}}\nقبلتها Meta: {{6}}\nرفضتها Meta: {{7}}\nنتيجة غير مؤكدة أو قيد الإرسال: {{8}}\nلم تبدأ محاولتها عند الملخص: {{9}}\nمحاولات محجوبة أو ملغاة: {{10}}\nعمال مستبعدون من الإرسال: {{11}}\nمن المقبول أعلاه، تعذر تسليمه: {{12}}\nقبول Meta لا يؤكد التسليم. هذا ملخص المحاولات الأولية، وليس اكتمال إجابات العمال.";
    private final ReportingStore store;
    private final ReportingService reporting;
    private final ReportingSettings settings;
    private final Clock clock;
    private final ObjectMapper mapper;
    public DailyReportingService(ReportingStore store,ReportingService reporting,ReportingSettings settings,Clock clock,ObjectMapper mapper) {
        this.store=store;this.reporting=reporting;this.settings=settings;this.clock=clock;this.mapper=mapper;
    }
    static Instant opening(LocalDate date) {return date.atTime(19,0).atZone(ZONE).toInstant();}
    static Instant cutoff(LocalDate date) {return date.atTime(19,10).atZone(ZONE).toInstant();}
    static Instant expiry(LocalDate date) {return date.atTime(19,30).atZone(ZONE).toInstant();}
    private LocalDate today() {return LocalDate.now(clock.withZone(ZONE));}
    private boolean creationWindow(LocalDate date) {return !clock.instant().isBefore(opening(date)) && clock.instant().isBefore(cutoff(date));}

    public Map<String,Object> preview(LocalDate date) {
        if(date==null) throw new ReportingProblem(400,"workDate is required.");
        return store.transaction(() -> {
            List<DailyMember> members=store.activeWorkers(date).stream().map(w -> member("preview",w,null)).toList();
            int configured=(int)members.stream().filter(DailyMember::configured).count();
            int selected=(int)members.stream().filter(m -> m.exclusionCode()==null).count();
            List<String> p=parameters(date,"معاينة فقط؛ لم تُرسل أي رسالة",members.size(),configured,selected,0,0,0,selected,0,members.size()-selected,0);
            return Map.of("workDate",date,"automationEnabled",settings.dailyEnabled,"rolloutEnabled",settings.rolloutEnabled,"configurationReady",settings.dailyReady(),
                    "creationWindowOpen",date.equals(today()) && creationWindow(date),"active",members.size(),"configured",configured,"selected",selected,
                    "workers",members,"summaryPreview",render(p));
        });
    }
    public Map<String,Object> trigger(LocalDate date) {
        if(!settings.dailyReady()) throw new ReportingProblem(503,"Daily automation, shared sending, worker template and both management recipients/template must be explicitly configured and enabled.");
        if(date==null || !date.equals(today())) throw new ReportingProblem(400,"Daily runs only accept today's Asia/Hebron work date.");
        DailyRun existing=store.runByDate(date,settings.phoneId);
        if(existing==null && !creationWindow(date)) throw new ReportingProblem(409,"New daily runs are limited to 19:00 inclusive through 19:10 exclusive Asia/Hebron.");
        DailyRun run=existing!=null ? existing : store.transaction(() -> store.ensureRun(date,settings.phoneId,clock.instant(),cutoff(date),expiry(date),settings.ownerRecipient,settings.fatherRecipient,settings.managementLanguage));
        try {prepare(run.id());}
        catch(RuntimeException failure) {
            // Preparation is atomic; never represent an incomplete scan/SQL failure as zero workers.
            store.transaction(() -> {DailyRun r=store.lockRun(run.id());if(r.summaryParameters()==null)store.saveRun(r.phase("FAILED","PREPARATION_FAILED"));return null;});
        }
        finalizeIfReady(run.id());
        return inspect(date);
    }
    public void tick() {
        if(!settings.dailyReady()) return; // Disabled startup does not read reporting tables.
        LocalDate date=today();
        if(clock.instant().isBefore(opening(date))) return;
        DailyRun run=store.runByDate(date,settings.phoneId);
        if(run!=null && run.summaryParameters()!=null) return; // Frozen content needs no recurring roster scan.
        if(creationWindow(date) || run!=null) trigger(date);
    }
    private void prepare(String id) {
        store.transaction(() -> {
            DailyRun run=store.lockRun(id);
            if(!run.phase().equals("NEW")) return null;
            if(!creationWindow(run.workDate())) {store.saveRun(run.phase("FAILED","PREPARATION_WINDOW_MISSED"));return null;}
            for(Worker projected:store.activeWorkers(run.workDate())) {
                Worker w=store.lockWorker(projected.id());DailyMember member=member(id,w,projected.id());
                if(member.exclusionCode()==null) {
                    try {
                        Request r=reporting.ensureRequest(w.id(),run.workDate(),id);
                        member=new DailyMember(id,w.id(),true,null,r.id());
                    } catch(ReportingProblem conflict) {
                        member=new DailyMember(id,w.id(),member.configured(),"REQUEST_SETUP_CONFLICT",null);
                    }
                }
                store.addRunMember(member);
            }
            store.saveRun(run.phase("DISPATCHING",null));return null;
        });
    }
    private DailyMember member(String run,Worker w,Long missingId) {
        boolean configured=w!=null && w.eligible() && ReportingSettings.canonicalNumber(w.number());
        String reason=w==null ? "WORKER_REMOVED" : !w.eligible() ? "PARTICIPATION_DISABLED" : !ReportingSettings.canonicalNumber(w.number()) ? "NUMBER_MISSING_OR_INVALID"
                : settings.managementNumberMatches(w.number()) ? "MANAGEMENT_RECIPIENT" : !settings.allowsWorker(w) ? "ROLLOUT_RESTRICTED" : null;
        return new DailyMember(run,w==null ? missingId : w.id(),configured,reason,null);
    }
    private record Counts(int active,int configured,int selected,int accepted,int rejected,int uncertain,int pending,int blocked,int skipped,int deliveryFailed) { }
    private Counts counts(String runId) {
        int configured=0,selected=0,accepted=0,rejected=0,uncertain=0,pending=0,blocked=0,skipped=0,deliveryFailed=0;
        List<DailyMember> members=store.runMembers(runId);
        for(DailyMember m:members) {
            if(m.configured())configured++;
            if(m.requestId()==null) {skipped++;continue;}
            selected++;
            Outbound opening=store.outboundFor(m.requestId()).stream().filter(o -> o.kind().equals("TEMPLATE")).findFirst().orElse(null);
            if(opening==null) {blocked++;continue;}
            switch(opening.state()) {
                case "ACCEPTED" -> {accepted++;if("FAILED".equals(opening.delivery()))deliveryFailed++;}
                case "REJECTED" -> rejected++;
                case "SENDING","UNCERTAIN" -> uncertain++;
                case "PENDING" -> pending++;
                default -> blocked++;
            }
        }
        return new Counts(members.size(),configured,selected,accepted,rejected,uncertain,pending,blocked,skipped,deliveryFailed);
    }
    private void finalizeIfReady(String id) {
        store.transaction(() -> {
            DailyRun run=store.lockRun(id);
            if(run.summaryParameters()!=null) return null;
            Counts c=counts(id);
            boolean inFlight=store.runMembers(id).stream().filter(m -> m.requestId()!=null).flatMap(m -> store.outboundFor(m.requestId()).stream()).anyMatch(o -> o.kind().equals("TEMPLATE") && o.state().equals("SENDING"));
            if(run.phase().equals("DISPATCHING") && (c.pending()>0 || inFlight) && clock.instant().isBefore(run.cutoffAt())) return null;
            if(run.phase().equals("NEW")) return null;
            boolean failed=run.failureCode()!=null;
            String state=failed ? "فشلت الجولة؛ البيانات غير مكتملة" : !clock.instant().isBefore(run.expiresAt()) ? "فاتت نافذة الملخص؛ راجع الجولة" : c.selected()==0 ? "لم تتم مراسلة أي عامل؛ راجع الاستبعادات" : c.rejected()+c.uncertain()+c.pending()+c.blocked()+c.deliveryFailed()+c.skipped()>0 ? "نتائج جزئية؛ يلزم التحقق والمتابعة" : "اكتملت المحاولات الأولية؛ قبول Meta فقط";
            List<String> p=parameters(run.workDate(),state,c.active(),c.configured(),c.selected(),c.accepted(),c.rejected(),c.uncertain(),c.pending(),c.blocked(),c.skipped(),c.deliveryFailed());
            if(failed) for(int n=2;n<p.size();n++)p.set(n,"غير متاح");
            String text=render(p);
            store.saveRun(run.snapshot(text,json(p)));
            for(var target:Map.of("OWNER",run.ownerRecipient(),"FATHER",run.fatherRecipient()).entrySet()) {
                String payload=template(target.getValue(),run.language(),p);
                store.insertOutbound(new Outbound(UUID.randomUUID().toString(),null,0,"MGMT_SUMMARY",payload,"PENDING",null,null,clock.instant(),"MANAGEMENT",target.getKey(),target.getValue(),run.phoneId(),run.id(),null));
            }
            return null;
        });
    }
    public Map<String,Object> inspect(LocalDate date) {
        DailyRun run=store.runByDate(date,settings.phoneId);
        if(run==null) throw new ReportingProblem(404,"Daily run not found for this business sender and date.");
        return store.transaction(() -> {
            DailyRun r=store.lockRun(run.id());Map<String,Object> result=new LinkedHashMap<>();
            result.put("id",r.id());result.put("workDate",r.workDate());result.put("phase",r.phase());result.put("failureCode",r.failureCode());result.put("cutoffAt",r.cutoffAt());result.put("expiresAt",r.expiresAt());
            boolean known=r.failureCode()==null && !r.phase().equals("NEW");
            result.put("summarySnapshot",r.summaryText());result.put("summaryParameters",r.summaryParameters());result.put("workerCountsKnown",known);result.put("currentWorkerCounts",known ? counts(r.id()) : null);
            result.put("workers",store.runMembers(r.id()));result.put("management",store.managementOutbound(r.id()).stream().map(ReportingService::outboundView).toList());return result;
        });
    }
    private static List<String> parameters(LocalDate date,String state,int... counts) {
        List<String> p=new ArrayList<>();p.add(date.format(DateTimeFormatter.ofPattern("EEEE، dd/MM/uuuu",Locale.forLanguageTag("ar"))));p.add(state);
        for(int count:counts)p.add(Integer.toString(count));return p;
    }
    static String render(List<String> parameters) {String body=BODY;for(int i=0;i<parameters.size();i++)body=body.replace("{{"+(i+1)+"}}",parameters.get(i));if(body.codePointCount(0,body.length())>1024)throw new IllegalStateException("Summary exceeds bounded template size");return body;}
    private String template(String to,String language,List<String> p) {
        return json(Map.of("messaging_product","whatsapp","to",to,"type","template","template",Map.of("name",TEMPLATE,"language",Map.of("code",language),"components",List.of(Map.of("type","body","parameters",p.stream().map(v -> Map.of("type","text","text",v)).toList())))));
    }
    private String json(Object value) {try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException("Could not build daily reporting snapshot");}}
}
