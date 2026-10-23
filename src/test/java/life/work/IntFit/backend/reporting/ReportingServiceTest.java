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

class ReportingServiceTest {
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
    @Test void multiSiteCoworkersSubmissionIsImmutableAndOriginalDateSurvivesMidnight() {
        String id=initiate().id();act(id,"start",0);act(id,"site",11);act(id,"cow",8);
        assertEquals(1,store.mentions(id).size());act(id,"cow",8);assertTrue(store.mentions(id).isEmpty());act(id,"cow",8);
        act(id,"done",0);act(id,"site",12);act(id,"done",0);act(id,"finish",0);
        clock.now=Instant.parse("2026-10-23T00:30:00Z");act(id,"submit",0);
        ReportView report=service.report(id);
        assertEquals(LocalDate.of(2026,10,22),report.workDate());assertEquals("AWAITING_REVIEW",report.reviewStatus());
        assertEquals("WORKED",report.outcome());assertEquals(2,report.sites().size());assertEquals(1,report.coworkers().size());
        Request original=store.request(id);actAfterSubmission(id);assertEquals(original,store.request(id));
        dispatch(id);assertTrue(sent.getLast().contains("بانتظار مراجعة"));
        assertEquals(1,store.requests.size());assertFalse(sent.toString().contains("غير مؤهل"));
    }
    void actAfterSubmission(String id) {
        service.acceptVerifiedValue(value("wamid.after",ReportingFlow.token(store.request(id),"submit",0,0),"wamid.out1","button_reply"));
    }
    @Test void didNotWorkRequiresFinalConfirmationAndCreatesNoSitesOrCoworkerRequests() {
        String id=initiate().id();act(id,"none",0);assertNull(store.request(id).submittedAt());act(id,"submit",0);
        assertEquals("DID_NOT_WORK",store.request(id).outcome());assertNotNull(store.request(id).submittedAt());
        assertTrue(store.sites(id).isEmpty());assertTrue(store.mentions(id).isEmpty());assertEquals(1,store.requests.size());
    }
    @Test void aNewDaysRequestDoesNotStealAnOlderRequestsReply() {
        String first=initiate().id();dispatch(first);String action=token(first,"start",0),context=current(first).metaId();
        clock.now=clock.now.plus(Duration.ofDays(1));String second=service.initiate(LocalDate.of(2026,10,23)).id();
        service.acceptVerifiedValue(value("wamid.oldDayReply",action,context,"button"));
        assertEquals("SITES",store.request(first).step());assertEquals(LocalDate.of(2026,10,22),store.request(first).workDate());
        assertEquals("START",store.request(second).step());assertEquals(0,store.request(second).revision());
    }
    @Test void noWorkCanGoBackBeforeSubmission() { String id=initiate().id();act(id,"none",0);act(id,"back",0);act(id,"start",0);assertEquals("SITES",store.request(id).step()); }
    @Test void initiationIdempotentPerWorkerWorkDateAndHasExactTemplateComponents() {
        ReportView r=initiate();assertEquals(r.id(),initiate().id());assertEquals(1,store.outbound.size());
        JsonNode t=map(current(r.id()).payload()).path("template");
        assertEquals("daily_work_report_ar",t.path("name").asText());assertEquals("ar",t.path("language").path("code").asText());
        assertEquals(3,t.path("components").size());assertEquals(1,t.path("components").get(0).path("parameters").size());
        assertTrue(t.path("components").get(0).path("parameters").get(0).path("text").asText().contains("22/10/2026"));
        assertEquals("0",t.path("components").get(1).path("index").asText());assertEquals("1",t.path("components").get(2).path("index").asText());
        assertEquals(0,sends.get());
    }
    @ParameterizedTest @ValueSource(strings={"WHATSAPP_REPORTING_ENABLED","WHATSAPP_REPORTING_SEND_ENABLED","WHATSAPP_REPORTING_TEMPLATE_CONFIRMED","WHATSAPP_REPORTING_TEMPLATE_LANGUAGE","WHATSAPP_REPORTING_PILOT_WORKER_ID","WHATSAPP_REPORTING_PILOT_RECIPIENT","WHATSAPP_REPORTING_PHONE_NUMBER_ID","WHATSAPP_ACCESS_TOKEN","WHATSAPP_REPORTING_ADMIN_SECRET"})
    void missingConfigurationPreventsSendingWithoutDatabaseOrStartupWork(String key) {
        env.remove(key);service=newService(p -> {fail("must not send");return null;});
        assertThrows(ReportingProblem.class,this::initiate);service.drainOne();assertEquals(0,store.transactions);assertTrue(store.outbound.isEmpty());
    }
    @Test void emptyConfigurationCreatesNoWorkAndWebhookProcessingIsInactive() {
        env.clear();service=newService(p -> {fail("send");return null;});service.drainOne();service.acceptVerifiedValue(map("{}"));
        new ReportingOutboxPump(new ReportingSettings(env::get),service).start();assertEquals(0,store.transactions);
    }
    @Test void explicitWorkerEligibilityNumberAndSelectableSitesAreRequired() {
        store.workers.put(7L,new Worker(7,"عامل",false,null));assertThrows(ReportingProblem.class,this::initiate);
        store.workers.put(7L,new Worker(7,"عامل",true,"972599000007"));assertThrows(ReportingProblem.class,this::initiate);
        store.workers.put(7L,new Worker(7,"عامل",true,"970599000007"));store.selectable.clear();assertThrows(ReportingProblem.class,this::initiate);
        assertTrue(store.requests.isEmpty());assertEquals(0,sends.get());
    }
    @Test void phoneNormalizationDoesNotInferOrExchangePrefixesAndUniqueNumbersAreEnforcedByFake() {
        assertEquals("970599000007",ReportingSettings.normalizeNumber("+970599000007"));
        assertEquals("972599000007",ReportingSettings.normalizeNumber("+972599000007"));
        for(String bad:List.of("0599 000007"," 970599000007","00970599000007","+970-599000007")) assertThrows(ReportingProblem.class,() -> ReportingSettings.normalizeNumber(bad));
        assertThrows(org.springframework.dao.DuplicateKeyException.class,() -> service.configureWorker(8,true,"+970599000007"));
        assertEquals("972599000008",store.workers.get(8L).number());
    }
    @Test void wrongBusinessNumberOrSenderDoesNotEnterPersistence() {
        String id=initiate().id();dispatch(id);int before=store.transactions;
        ObjectNode v=(ObjectNode)value("wamid.a",token(id,"start",0),current(id).metaId(),"button");
        ((ObjectNode)v.path("metadata")).put("phone_number_id","999900009");service.acceptVerifiedValue(v);
        ((ObjectNode)v.path("metadata")).put("phone_number_id","123400007");((ObjectNode)v.path("messages").get(0)).put("from","972599000007");service.acceptVerifiedValue(v);
        assertEquals(before,store.transactions);assertTrue(store.inbox.isEmpty());
    }
    @Test void forgedActionWrongContextWrongRequestAndOldStepAreIgnored() {
        String id=initiate().id();dispatch(id);String original=token(id,"start",0);String context=current(id).metaId();
        service.acceptVerifiedValue(value("wamid.bad1",original,"wamid.unrelated","button"));
        service.acceptVerifiedValue(value("wamid.bad2",original.replace(id,UUID.randomUUID().toString()),context,"button"));
        service.acceptVerifiedValue(value("wamid.bad3",ReportingFlow.token(store.request(id),"site",13,0),context,"button"));
        assertEquals("START",store.request(id).step());
        service.acceptVerifiedValue(value("wamid.good",original,context,"button"));
        service.acceptVerifiedValue(value("wamid.stale",original,context,"button"));
        assertEquals(1,store.request(id).revision());assertEquals(2,store.outbound.size());
    }
    @Test void duplicateAndConcurrentDifferentMessageIdsCannotApplySameStepTwice() throws Exception {
        String id=initiate().id();dispatch(id);String action=token(id,"start",0),context=current(id).metaId();
        JsonNode first=value("wamid.same",action,context,"button");service.acceptVerifiedValue(first);service.acceptVerifiedValue(first);
        try(ExecutorService pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> service.acceptVerifiedValue(value("wamid.concurrent1",action,context,"button")));
            var b=pool.submit(() -> service.acceptVerifiedValue(value("wamid.concurrent2",action,context,"button")));
            a.get(2,TimeUnit.SECONDS);b.get(2,TimeUnit.SECONDS);
        }
        assertEquals(1,store.request(id).revision());assertEquals(2,store.outbound.size());assertEquals(3,store.inbox.size());
    }
    @Test void failedEventCommitRollsBackStateInboxAndOutgoingIntentThenRedeliveryWorks() {
        String id=initiate().id();dispatch(id);JsonNode event=value("wamid.rollback",token(id,"start",0),current(id).metaId(),"button");
        store.failCommit=true;assertThrows(IllegalStateException.class,() -> service.acceptVerifiedValue(event));
        assertEquals("START",store.request(id).step());assertEquals(1,store.outbound.size());assertTrue(store.inbox.isEmpty());
        service.acceptVerifiedValue(event);assertEquals("SITES",store.request(id).step());assertEquals(2,store.outbound.size());assertEquals(1,store.inbox.size());
    }
    @Test void stateAndPendingIntentSurviveServiceRecreationUsingSameFakeStore() {
        String id=initiate().id();act(id,"start",0);
        service=newService(p -> {sends.incrementAndGet();return new ReportingSender.Result("ACCEPTED","wamid.restarted");});
        service.drainOne();assertEquals("ACCEPTED",current(id).state());assertEquals("SITES",service.report(id).step());
    }
    @Test void acceptanceBeforeResultCommitLeavesSendingAndIsNeverBlindlyRetried() {
        String id=initiate().id();
        service=newService(p -> {sends.incrementAndGet();store.failCommit=true;return new ReportingSender.Result("ACCEPTED","wamid.acceptedBeforeCrash");});
        assertThrows(IllegalStateException.class,() -> dispatch(id));assertEquals("SENDING",current(id).state());
        service=newService(p -> {fail("uncertain attempt must not resend");return null;});
        service.drainOne();dispatch(id);assertEquals(1,sends.get());
        service.resolve(current(id).id(),"accepted","wamid.acceptedBeforeCrash");assertEquals("ACCEPTED",current(id).state());
        service.acceptVerifiedValue(value("wamid.recoveryreply",token(id,"start",0),"wamid.acceptedBeforeCrash","button"));
        assertEquals("SITES",store.request(id).step());
    }
    @Test void replyBeforeAcceptanceCommitIsDeferredThenAppliedWithMatchingContext() {
        String id=initiate().id();String action=token(id,"start",0);
        service=newService(p -> {
            service.acceptVerifiedValue(value("wamid.early",action,"wamid.earlyAccepted","button"));
            assertEquals("START",store.request(id).step());assertEquals("DEFERRED",store.inbox.values().iterator().next().disposition());
            return new ReportingSender.Result("ACCEPTED","wamid.earlyAccepted");
        });
        dispatch(id);assertEquals("SITES",store.request(id).step());assertEquals("APPLIED",store.inbox.values().iterator().next().disposition());
    }
    @Test void replyWhileUncertainSurvivesServiceRecreationAndReconcilesWithoutResend() {
        String id=initiate().id();String action=token(id,"start",0);
        service=newService(p -> {sends.incrementAndGet();return new ReportingSender.Result("UNCERTAIN",null);});dispatch(id);
        service.acceptVerifiedValue(value("wamid.deferred",action,"wamid.evidence","button"));assertEquals("DEFERRED",store.inbox.values().iterator().next().disposition());
        service=newService(p -> {fail("Must not resend uncertain template");return null;});
        service.resolve(current(id).id(),"accepted","wamid.evidence");assertEquals("SITES",store.request(id).step());assertEquals(1,sends.get());
        assertEquals("APPLIED",store.inbox.values().iterator().next().disposition());
    }
    @Test void deferredReplyWithWrongContextNeverChangesConversation() {
        String id=initiate().id();String action=token(id,"none",0);
        service=newService(p -> {service.acceptVerifiedValue(value("wamid.wrongEarly",action,"wamid.wrong","button"));return new ReportingSender.Result("ACCEPTED","wamid.right");});
        dispatch(id);assertEquals("START",store.request(id).step());assertEquals("IGNORED",store.inbox.values().iterator().next().disposition());
    }
    @Test void acceptedReconciliationWorksWhileSendingIsPausedAndDoesNotSend() {
        String id=initiate().id();String action=token(id,"start",0);
        service=newService(p -> new ReportingSender.Result("UNCERTAIN",null));dispatch(id);
        env.remove("WHATSAPP_REPORTING_SEND_ENABLED");service=newService(p -> {fail("Recovery cannot send");return null;});
        service.acceptVerifiedValue(value("wamid.pauseRecovery",action,"wamid.proven","button"));
        service.resolve(current(id).id(),"accepted","wamid.proven");assertEquals("SITES",store.request(id).step());assertEquals("PENDING",current(id).state());
        service.drainOne();assertEquals(0,sends.get());
    }
    @Test void failedClaimCommitNeverCallsSenderAndPendingCanBeAbandoned() {
        String id=initiate().id();store.failCommit=true;assertThrows(IllegalStateException.class,() -> dispatch(id));
        assertEquals("PENDING",current(id).state());assertEquals(0,sends.get());
        service.resolve(current(id).id(),"abandon",null);assertEquals("CANCELLED",current(id).state());service.drainOne();assertEquals(0,sends.get());
    }
    @Test void pausedSendingStillPersistsValidRepliesAndTheirPendingIntents() {
        String id=initiate().id();dispatch(id);String action=token(id,"start",0);String context=current(id).metaId();
        env.remove("WHATSAPP_REPORTING_SEND_ENABLED");service=newService(p -> {fail("Paused");return null;});
        service.acceptVerifiedValue(value("wamid.pausedReply",action,context,"button"));
        assertEquals("SITES",store.request(id).step());assertEquals("PENDING",current(id).state());assertThrows(ReportingProblem.class,() -> dispatch(id));
    }
    @ParameterizedTest @ValueSource(strings={"REJECTED","UNCERTAIN"}) void failedOrUncertainSendIsOneAttemptWithoutRetry(String state) {
        String id=initiate().id();service=newService(p -> {sends.incrementAndGet();return new ReportingSender.Result(state,null);});
        dispatch(id);dispatch(id);service.drainOne();assertEquals(1,sends.get());assertEquals(state,current(id).state());
    }
    @Test void concurrentDispatchDoesNotResendAlreadySendingIntent() throws Exception {
        String id=initiate().id();CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        service=newService(p -> {sends.incrementAndGet();entered.countDown();try{assertTrue(release.await(3,TimeUnit.SECONDS));}catch(InterruptedException e){throw new AssertionError(e);}return new ReportingSender.Result("ACCEPTED","wamid.concurrent");});
        try(ExecutorService pool=Executors.newSingleThreadExecutor()) {
            Future<?> running=pool.submit(() -> dispatch(id));assertTrue(entered.await(2,TimeUnit.SECONDS));
            dispatch(id);release.countDown();running.get(3,TimeUnit.SECONDS);
        }
        assertEquals(1,sends.get());assertEquals("ACCEPTED",current(id).state());
    }
    @Test void expiredTemplateAndExpiredInteractiveWindowAreCancelledWithoutSending() {
        String id=initiate().id();clock.now=clock.now.plus(Duration.ofDays(1));dispatch(id);assertEquals("CANCELLED",current(id).state());assertEquals(0,sends.get());
        setup();id=initiate().id();act(id,"start",0);clock.now=clock.now.plus(Duration.ofHours(24));dispatch(id);assertEquals("CANCELLED",current(id).state());assertEquals(1,sends.get());
    }
    @Test void revokedEligibilityOrChangedNumberBlocksAlreadyPendingSend() {
        String id=initiate().id();service.configureWorker(7,false,"970599000007");assertThrows(ReportingProblem.class,() -> dispatch(id));assertEquals(0,sends.get());
    }
    @Test void batchedStatusEventsCanArriveBeforeAcceptanceAndNeverRegressRead() {
        String id=initiate().id();JsonNode status=map("{\"metadata\":{\"phone_number_id\":\"123400007\"},\"statuses\":[{\"id\":\"wamid.out1\",\"recipient_id\":\"970599000007\",\"status\":\"read\",\"timestamp\":\"1792660000\"},{\"id\":\"wamid.out1\",\"recipient_id\":\"970599000007\",\"status\":\"sent\",\"timestamp\":\"1792660001\"}]}");
        service.acceptVerifiedValue(status);dispatch(id);assertEquals("READ",current(id).delivery());service.acceptVerifiedValue(status);assertEquals(2,store.inbox.size());
    }
    @Test void paginationRespectsTenRowLimitAndOnlyExplicitChoicesAppear() {
        for(long i=20;i<45;i++){store.choices.put(i,new Choice(i,"موقع "+i));store.selectable.add(i);}
        String id=initiate().id();act(id,"start",0);JsonNode prompt=map(current(id).payload());
        assertTrue(prompt.path("interactive").path("action").path("sections").get(0).path("rows").size()<=10);
        act(id,"page",0);assertEquals("SITES",store.request(id).step());assertFalse(map(current(id).payload()).path("interactive").toString().contains("970599"));
    }
    @Test void workerFacingChoicesExcludeContactWagesAndUnselectableSites() {
        String id=initiate().id();act(id,"start",0);JsonNode p=map(current(id).payload());String choices=p.path("interactive").toString();
        assertFalse(choices.contains("970599"));assertFalse(choices.contains("wage"));assertFalse(choices.contains("موقع غير مختار"));
        act(id,"site",11);String c=map(current(id).payload()).path("interactive").toString();assertTrue(c.contains("زميل"));assertFalse(c.contains("972599"));assertFalse(c.contains("غير مؤهل"));
    }
    static final class MutableClock extends Clock {
        Instant now=Instant.parse("2026-10-22T16:00:00Z");final ZoneId zone;
        MutableClock(){this.zone=ZoneId.of("Asia/Hebron");}
        MutableClock(Instant i,ZoneId z){now=i;zone=z;}
        @Override public ZoneId getZone(){return zone;}@Override public Clock withZone(ZoneId z){return new MutableClock(now,z);}@Override public Instant instant(){return now;}
    }
}
