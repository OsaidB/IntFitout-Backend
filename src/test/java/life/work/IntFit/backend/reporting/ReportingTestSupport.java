package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static life.work.IntFit.backend.reporting.ReportingModel.*;

abstract class ReportingTestSupport {
    final ObjectMapper mapper=new ObjectMapper();
    MemoryReportingStore store;
    Map<String,String> env;
    MutableClock clock;
    ReportingService service;
    AtomicInteger sends;
    List<String> sent;
    @BeforeEach void setup() {
        store=new MemoryReportingStore(); env=environment();clock=new MutableClock();sends=new AtomicInteger();sent=new ArrayList<>();
        store.workers.put(7L,new Worker(7,"عامل تجريبي",true,"970599000007"));
        store.workers.put(8L,new Worker(8,"زميل",true,"972599000008"));
        store.workers.put(9L,new Worker(9,"غير مؤهل",false,null));
        store.choices.put(11L,new Choice(11,"الموقع الأول"));store.choices.put(12L,new Choice(12,"الموقع الثاني"));
        store.choices.put(13L,new Choice(13,"موقع غير مختار"));store.selectable.addAll(Set.of(11L,12L));
        store.activity.add(new MemoryReportingStore.Activity(7,11,LocalDate.of(2026,10,22)));
        store.activity.add(new MemoryReportingStore.Activity(7,12,LocalDate.of(2026,10,22)));
        service=newService(payload -> { sent.add(payload);return new ReportingSender.Result("ACCEPTED","wamid.out"+sends.incrementAndGet()); });
    }
    static Map<String,String> environment() {
        Map<String,String> e=new HashMap<>();
        e.put("WHATSAPP_REPORTING_ENABLED","true"); e.put("WHATSAPP_REPORTING_SEND_ENABLED","true");
        e.put("WHATSAPP_REPORTING_TEMPLATE_CONFIRMED","true");e.put("WHATSAPP_REPORTING_TEMPLATE_LANGUAGE","ar");
        e.put("WHATSAPP_REPORTING_ADMIN_SECRET","synthetic-report-admin-secret-ONLY");
        e.put("WHATSAPP_ACCESS_TOKEN","synthetic-access-token-ONLY");
        e.put("WHATSAPP_REPORTING_PHONE_NUMBER_ID","123400007");
        e.put("WHATSAPP_PHONE_NUMBER_ID","999900009"); // Must never be used by reporting.
        e.put("WHATSAPP_REPORTING_PILOT_RECIPIENT","970599000007");e.put("WHATSAPP_REPORTING_PILOT_WORKER_ID","7");
        return e;
    }
    ReportingService newService(ReportingSender sender) { return new ReportingService(store,new ReportingSettings(env::get),sender,clock,mapper); }
    ReportView initiate() { return service.initiate(LocalDate.of(2026,10,22)); }
    Outbound current(String request) { return store.outboundFor(request).getLast(); }
    void dispatch(String id) { service.dispatch(current(id).id()); }
    String token(String id,String verb,long target) {
        Outbound o=current(id);
        List<String> tokens=new ArrayList<>();collect(map(o.payload()),tokens);
        return tokens.stream().filter(t -> { var a=ReportingFlow.action(t);return a!=null && a.verb().equals(verb) && a.id()==target; }).findFirst().orElseThrow();
    }
    static void collect(JsonNode n,List<String> tokens) { if(n.isTextual() && n.asText().startsWith("wr:")) tokens.add(n.asText());for(JsonNode c:n)collect(c,tokens); }
    JsonNode map(String s) { try{return mapper.readTree(s);}catch(Exception e){throw new AssertionError(e);} }
    JsonNode value(String messageId,String action,String context,String type) {
        ObjectNode value=mapper.createObjectNode(); value.putObject("metadata").put("phone_number_id","123400007");
        ObjectNode m=value.putArray("messages").addObject();m.put("id",messageId).put("from","970599000007").put("timestamp",Long.toString(clock.instant().getEpochSecond()));
        m.putObject("context").put("id",context);
        if(type.equals("button")) {m.put("type","button");m.putObject("button").put("payload",action).put("text","غير مستخدم");}
        else {m.put("type","interactive");m.putObject("interactive").put("type",type).putObject(type).put("id",action).put("title","غير مستخدم");}
        return value;
    }
    void act(String request,String verb,long target) {
        dispatch(request); Outbound o=current(request);
        String type=o.kind().equals("TEMPLATE") ? "button" : map(o.payload()).path("interactive").path("type").asText().equals("list") ? "list_reply" : "button_reply";
        service.acceptVerifiedValue(value("wamid.in"+UUID.randomUUID(),token(request,verb,target),o.metaId(),type));
    }
    void actAfterSubmission(String id) {
        service.acceptVerifiedValue(value("wamid.after",ReportingFlow.token(store.request(id),"submit",0,0),"wamid.out1","button_reply"));
    }
    static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-22T16:00:00Z");final ZoneId zone;
        MutableClock(){this.zone=ZoneId.of("Asia/Hebron");}
        MutableClock(Instant i,ZoneId z){now=i;zone=z;}
        @Override public ZoneId getZone(){return zone;}@Override public Clock withZone(ZoneId z){return new MutableClock(now,z);}@Override public Instant instant(){return now;}
    }
}
