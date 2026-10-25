package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static life.work.IntFit.backend.reporting.ReportingModel.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Controlled-clock, transactional fake and mocked sender; not proof of MySQL behavior. */
class DailyReportingTest extends ReportingTestSupport {
    static final LocalDate DAY=LocalDate.of(2026,10,22);
    @BeforeEach void dailyConfiguration() {
        env.putAll(dailyEnvironment());clock.now=DailyReportingService.opening(DAY);service=newService(p -> {sent.add(p);return new ReportingSender.Result("ACCEPTED","wamid.daily"+sends.incrementAndGet());});
        store.activity.add(new MemoryReportingStore.Activity(8,11,DAY.minusDays(6)));
    }
    static Map<String,String> dailyEnvironment() {return Map.of("WHATSAPP_REPORTING_DAILY_ENABLED","true","WHATSAPP_REPORTING_ROLLOUT_ENABLED","true","WHATSAPP_REPORTING_MANAGEMENT_OWNER_RECIPIENT","+970599000099","WHATSAPP_REPORTING_MANAGEMENT_FATHER_RECIPIENT","+972599000098","WHATSAPP_REPORTING_MANAGEMENT_TEMPLATE_LANGUAGE","ar","WHATSAPP_REPORTING_MANAGEMENT_TEMPLATE_CONFIRMED","true");}
    DailyReportingService daily() {return new DailyReportingService(store,service,new ReportingSettings(env::get),clock,mapper);}
    DailyRun run() {return store.runByDate(DAY,"123400007");}
    void drain() {for(int i=0;i<10 && !store.pendingIds(1).isEmpty();i++)service.drainOne();}
    String request(long worker) {return store.request(worker,DAY).id();}
    void action(long worker,String request,String verb) {
        dispatch(request);Outbound o=current(request);ObjectNode v=(ObjectNode)value("wamid.in"+UUID.randomUUID(),token(request,verb,0),o.metaId(),o.kind().equals("TEMPLATE") ? "button" : "list_reply");
        ((ObjectNode)v.path("messages").get(0)).put("from",store.workers.get(worker).number());service.acceptVerifiedValue(v);
    }
    JsonNode text(long worker,String body,String context) {
        ObjectNode v=(ObjectNode)value("wamid.text"+UUID.randomUUID(),"",context,"button");ObjectNode m=(ObjectNode)v.path("messages").get(0);m.put("from",store.workers.get(worker).number()).put("type","text");m.remove("button");if(context==null)m.remove("context");m.putObject("text").put("body",body);return v;
    }
    @Test void disabledDefaultsDoNotReadStoreOrStartSchedulerAndNoPastDayCatchup() {
        env.put("WHATSAPP_REPORTING_DAILY_ENABLED","false");daily().tick();assertEquals(0,store.transactions);assertTrue(store.runs.isEmpty());
        var scheduler=new DailyReportingScheduler(new ReportingSettings(env::get),daily(),() -> {fail("disabled scheduler");return null;});scheduler.start();scheduler.stop();
        env.put("WHATSAPP_REPORTING_DAILY_ENABLED","true");clock.now=DailyReportingService.cutoff(DAY);daily().tick();assertTrue(store.runs.isEmpty());
        assertThrows(ReportingProblem.class,() -> daily().trigger(DAY.minusDays(1)));
    }
    @Test void timezoneDstBoundariesAndTimingUseLocalCalendar() {
        for(LocalDate date:List.of(LocalDate.of(2026,3,27),LocalDate.of(2026,3,29),LocalDate.of(2026,10,23),LocalDate.of(2026,10,25))) {
            assertEquals(LocalTime.of(19,0),DailyReportingService.opening(date).atZone(DailyReportingService.ZONE).toLocalTime());
            assertEquals(date,DailyReportingService.opening(date).atZone(DailyReportingService.ZONE).toLocalDate());
        }
        clock.now=DailyReportingService.opening(DAY).minusSeconds(1);daily().tick();assertTrue(store.runs.isEmpty());clock.now=clock.now.plusSeconds(1);daily().tick();assertNotNull(run());
    }
    @Test void rolloutIsSeparateFromParticipationAndIncludesExistingTodayAssignments() {
        env.put("WHATSAPP_REPORTING_ROLLOUT_ENABLED","false");service=newService(p -> new ReportingSender.Result("ACCEPTED","wamid.pilot"));daily().trigger(DAY);
        assertEquals(2,store.runMembers(run().id()).size());assertNotNull(store.request(7,DAY));assertNull(store.request(8,DAY));assertTrue(store.runMembers(run().id()).stream().anyMatch(m -> "ROLLOUT_RESTRICTED".equals(m.exclusionCode())));
        assertEquals(0,sends.get()); // Initiation is durable, never sends inline.
        drain();daily().tick();assertTrue(run().summaryText().contains("نتائج جزئية"));
    }
    @Test void oneSnapshotAndIdenticalManagementContentAndIndependentRecipientFailure() {
        daily().trigger(DAY);drain();daily().tick();assertEquals("SNAPSHOT",run().phase());String snapshot=run().summaryText();assertTrue(snapshot.contains("قبول Meta"));
        var management=store.managementOutbound(run().id());assertEquals(2,management.size());assertEquals(map(management.get(0).payload()).path("template"),map(management.get(1).payload()).path("template"));
        service=newService(p -> new ReportingSender.Result(map(p).path("to").asText().equals("970599000099") ? "REJECTED" : "ACCEPTED","wamid.managementResult"));drain();
        assertEquals(Set.of("ACCEPTED","REJECTED"),new HashSet<>(store.managementOutbound(run().id()).stream().map(Outbound::state).toList()));
        daily().tick();assertEquals(snapshot,run().summaryText());assertEquals(2,store.requests.size());assertEquals(2,store.managementOutbound(run().id()).size());
    }
    @Test void managementDoesNotNeedWorkerOrAssignmentRecordsAndZeroEligibleSummaryIsUseful() {
        store.workers.clear();store.activity.clear();env.remove("WHATSAPP_REPORTING_PILOT_WORKER_ID");env.remove("WHATSAPP_REPORTING_PILOT_RECIPIENT");env.put("WHATSAPP_REPORTING_ROLLOUT_ENABLED","false");service=newService(p -> {sends.incrementAndGet();return new ReportingSender.Result("ACCEPTED","wamid.noWorkers"+sends.get());});daily().trigger(DAY);assertTrue(store.requests.isEmpty());assertEquals(2,store.managementOutbound(run().id()).size());assertTrue(run().summaryText().contains("لم تتم مراسلة أي عامل"));drain();assertEquals(2,sends.get());
    }
    @Test void activeButDisabledOrIncompleteWorkersHaveExplicitReasonsAndManagementGetsNoQuestionnaire() {
        store.workers.put(7L,new Worker(7,"synthetic",true,"970599000099"));store.workers.put(8L,new Worker(8,"synthetic",false,null));
        daily().trigger(DAY);assertTrue(store.requests.isEmpty());assertTrue(store.runMembers(run().id()).stream().anyMatch(m -> "MANAGEMENT_RECIPIENT".equals(m.exclusionCode())));assertTrue(store.runMembers(run().id()).stream().anyMatch(m -> "PARTICIPATION_DISABLED".equals(m.exclusionCode())));assertEquals(2,store.managementOutbound(run().id()).size());
    }
    @Test void concurrentRepeatedTriggersAndRecreationAreHarmless() throws Exception {
        try(var pool=Executors.newFixedThreadPool(4)) {List<Future<?>> jobs=new ArrayList<>();for(int i=0;i<4;i++)jobs.add(pool.submit(() -> daily().trigger(DAY)));for(var job:jobs)job.get(5,TimeUnit.SECONDS);}
        assertEquals(1,store.runs.size());assertEquals(2,store.requests.size());assertEquals(2,store.outbound.size());
        drain();daily().tick();String snapshot=run().summaryText();service=newService(p -> {fail("no resending");return null;});daily().tick();assertEquals(snapshot,run().summaryText());assertEquals(2,store.managementOutbound(run().id()).size());
    }
    @Test void boundedCutoffNamesPendingUncertainAndNeverRetriesUnknownAcceptance() {
        daily().trigger(DAY);service=newService(p -> new ReportingSender.Result("UNCERTAIN",null));dispatch(request(7));assertNull(run().summaryText());
        clock.now=DailyReportingService.cutoff(DAY);daily().tick();JsonNode p=map(run().summaryParameters());assertEquals("1",p.get(7).asText());assertEquals("1",p.get(8).asText());assertTrue(run().summaryText().contains("نتائج جزئية"));
        assertEquals("CANCELLED",service.dispatch(current(request(8)).id()).state());assertEquals("UNCERTAIN",service.dispatch(current(request(7)).id()).state());
    }
    @Test void preparationFailureProducesUnknownIncompleteSummaryNotZeroSuccess() {
        store.transaction(() -> store.ensureRun(DAY,"123400007",clock.instant(),DailyReportingService.cutoff(DAY),DailyReportingService.expiry(DAY),"970599000099","972599000098","ar"));
        store.failCommit=true;daily().trigger(DAY);assertEquals("PREPARATION_FAILED",run().failureCode());assertTrue(run().summaryText().contains("غير مكتملة"));assertTrue(run().summaryText().contains("غير متاح"));assertTrue(store.requests.isEmpty());assertEquals(2,store.managementOutbound(run().id()).size());
    }
    @Test void blockedWorkerDoesNotStarveOtherWorkersOrManagementAndPauseIsGlobal() {
        daily().trigger(DAY);service.configureWorker(7,false,null);service.drainOne();assertEquals("CANCELLED",current(request(7)).state());assertEquals("ACCEPTED",current(request(8)).state());daily().tick();
        env.put("WHATSAPP_REPORTING_SEND_ENABLED","false");service=newService(p -> {fail("paused");return null;});service.drainOne();assertTrue(store.managementOutbound(run().id()).stream().allMatch(o -> o.state().equals("PENDING")));
    }
    @Test void workerRepliesAndContextlessOtherCaptureAreIsolatedAcrossWorkersAndMidnight() {
        daily().trigger(DAY);String a=request(7),b=request(8);action(7,a,"start");action(8,b,"start");action(7,a,"other");action(8,b,"other");dispatch(a);dispatch(b);
        service.acceptVerifiedValue(text(7,"اسم الموقع الأول",current(b).metaId()));assertEquals("OTHER_TEXT",store.request(a).step());assertTrue(store.sites(b).isEmpty());
        clock.now=clock.now.plus(Duration.ofHours(6));service.acceptVerifiedValue(text(7,"الموقع الأول",null));service.acceptVerifiedValue(text(8,"الموقع الثاني",null));assertEquals(1,store.sites(a).size());assertEquals(1,store.sites(b).size());assertEquals(DAY,store.request(a).workDate());
        ObjectNode management=(ObjectNode)text(7,"لا تغير طلب العامل",null);((ObjectNode)management.path("messages").get(0)).put("from","970599000099");int before=store.transactions;service.acceptVerifiedValue(management);assertEquals(before,store.transactions);
    }
    @Test void previewIsReadOnlyAndApprovalAndManagementConfigurationFailClosed() {
        var preview=daily().preview(DAY);assertEquals(2,preview.get("selected"));assertTrue(store.runs.isEmpty());assertTrue(store.outbound.isEmpty());
        env.put("WHATSAPP_REPORTING_MANAGEMENT_TEMPLATE_CONFIRMED","false");assertThrows(ReportingProblem.class,() -> daily().trigger(DAY));assertTrue(store.runs.isEmpty());
        env.put("WHATSAPP_REPORTING_MANAGEMENT_TEMPLATE_CONFIRMED","true");env.put("WHATSAPP_REPORTING_MANAGEMENT_FATHER_RECIPIENT","+970599000099");assertThrows(ReportingProblem.class,() -> daily().trigger(DAY));
    }
    @Test void snapshotsExpireWithoutLateManagementSendingAndSchedulerCleansUp() {
        daily().trigger(DAY);clock.now=DailyReportingService.expiry(DAY);daily().tick();drain();assertEquals(0,sends.get());assertTrue(store.managementOutbound(run().id()).stream().allMatch(o -> o.state().equals("CANCELLED")));
        var executor=new ReportingOutboxPumpTest.FakeScheduler();var scheduler=new DailyReportingScheduler(new ReportingSettings(env::get),daily(),() -> executor);scheduler.start();assertEquals(30,executor.delay);assertEquals(15,executor.interval);scheduler.stop();assertTrue(executor.stopped);
    }
    @Test void acceptedAndLaterDeliveryFailureAreNotDoubleCounted() {
        daily().trigger(DAY);drain();Outbound worker=current(request(7));
        ObjectNode v=mapper.createObjectNode();v.putObject("metadata").put("phone_number_id","123400007");v.putArray("statuses").addObject().put("id",worker.metaId()).put("recipient_id","970599000007").put("status","failed").put("timestamp",Long.toString(clock.instant().getEpochSecond()));service.acceptVerifiedValue(v);
        daily().tick();JsonNode p=map(run().summaryParameters());assertEquals("2",p.get(5).asText());assertEquals("0",p.get(6).asText());assertEquals("1",p.get(11).asText());assertFalse(run().summaryText().contains("اكتملت المحاولات"));
        String snapshot=run().summaryText();drain();daily().tick();assertEquals(snapshot,run().summaryText());
    }
    @Test void protectedPreviewAndTriggerRejectOverridesAndNeverExposePhoneLists() throws Exception {
        var settings=new ReportingSettings(env::get);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ReportingAdminController(settings,service,null,daily())).build();
        String base="/api/whatsapp/reporting/admin",header="X-WhatsApp-Reporting-Admin-Secret",secret=env.get("WHATSAPP_REPORTING_ADMIN_SECRET");
        mvc.perform(post(base+"/daily/preview").header(header,secret).contentType("application/json").content("{\"workDate\":\"2026-10-22\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.selected").value(2));
        for(String endpoint:List.of("/daily/preview","/daily-runs"))mvc.perform(post(base+endpoint).header(header,secret).contentType("application/json").content("{\"workDate\":\"2026-10-22\",\"recipient\":\"970599000099\"}")).andExpect(status().isBadRequest());
        mvc.perform(get(base+"/daily-runs/2026-10-22").header(header,secret).queryParam("recipient","970599000099")).andExpect(status().isBadRequest());
        assertTrue(store.runs.isEmpty());assertEquals(0,sends.get());
    }
    @Test void explicitRolloutDoesNotTurnTheLegacyManualPilotRouteIntoARecipientOverride() {
        service.configureWorker(7,true,"970599000070");assertThrows(ReportingProblem.class,() -> service.initiate(DAY));assertTrue(store.requests.isEmpty());assertEquals(0,sends.get());
    }
}
