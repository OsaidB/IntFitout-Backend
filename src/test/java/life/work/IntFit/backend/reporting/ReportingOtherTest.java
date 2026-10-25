package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static life.work.IntFit.backend.reporting.ReportingModel.*;
import static org.junit.jupiter.api.Assertions.*;

/** Conversation/clock tests using the explicit transactional fake, never a database or HTTP. */
class ReportingOtherTest extends ReportingTestSupport {
    JsonNode text(String id,String body,String context) {
        ObjectNode v=(ObjectNode)value(id,"",context,"button");ObjectNode m=(ObjectNode)v.path("messages").get(0);
        m.put("type","text");m.remove("button");if(context==null)m.remove("context");m.putObject("text").put("body",body);return v;
    }
    String capture() {String id=initiate().id();act(id,"start",0);act(id,"other",0);dispatch(id);return id;}
    void answer(String id,String body) {service.acceptVerifiedValue(text("wamid.text"+UUID.randomUUID(),body,current(id).metaId()));}
    @Test void calendarActivityBoundariesAreIndependentOfFlagsAndTodayAssignments() {
        store.activity.clear();LocalDate d=LocalDate.of(2026,10,22);
        for(LocalDate outside:List.of(d.minusDays(7),d.plusDays(1))) {
            store.activity.add(new MemoryReportingStore.Activity(7,11,outside));assertThrows(ReportingProblem.class,this::initiate);store.activity.clear();
        }
        store.selectable.clear();store.activity.add(new MemoryReportingStore.Activity(7,11,d.minusDays(6)));
        String id=initiate().id();assertTrue(store.activeWorker(7,d));assertFalse(store.activeSites(d).isEmpty());
        store.activity.clear();dispatch(id);assertEquals("NO_RECENT_ACTIVITY",current(id).failureCode());assertEquals(0,sends.get());
    }
    @Test void palestineCalendarDayUsedInsteadOfUtcAndOngoingRepliesKeepOriginalDate() {
        clock.now=Instant.parse("2026-10-22T21:30:00Z"); // 00:30 on 23 October in Hebron before DST end.
        assertThrows(ReportingProblem.class,this::initiate);
        clock.now=Instant.parse("2026-10-22T16:00:00Z");String id=capture();
        store.activity.clear();clock.now=Instant.parse("2026-10-23T22:00:00Z");answer(id,"موقع قرب المدرسة");
        assertEquals(LocalDate.of(2026,10,22),service.report(id).workDate());assertEquals("COWORKERS",store.request(id).step());
    }
    @Test void listedAndMultipleOtherEntriesHaveSeparateCoworkersAndImmutableOriginals() {
        String id=capture();answer(id,"  قرب المدرسة القديمة  ");act(id,"cow",8);act(id,"done",0);
        act(id,"site",11);act(id,"done",0);act(id,"other",0);dispatch(id);answer(id,"الطابق الثاني");act(id,"cow",8);act(id,"done",0);
        act(id,"finish",0);assertTrue(current(id).payload().contains("قرب المدرسة"));act(id,"submit",0);
        ReportView r=service.report(id);assertEquals(3,r.sites().size());assertEquals(2,r.coworkers().size());assertEquals("AWAITING_REVIEW",r.reviewStatus());
        assertEquals("قرب المدرسة القديمة",r.sites().getFirst().originalDescription());
        assertNotEquals(r.coworkers().get(0).entryId(),r.coworkers().get(1).entryId());assertNull(r.sites().getFirst().siteId());
        var before=store.request(id);answer(id,"لا تعدل التقرير");assertEquals(before,store.request(id));
    }
    @Test void otherOnlyWithNoRecentlyActiveKnownSitesAndCancellationAndInvalidText() {
        // Worker has assignment history to a master no longer present: Other still works.
        store.choices.clear();String id=capture();answer(id,"x");assertEquals("OTHER_TEXT",store.request(id).step());
        for(JsonNode button:map(current(id).payload()).path("interactive").path("action").path("buttons"))assertTrue(button.path("reply").path("title").asText().length()<=20);
        assertNull(store.inbox.values().stream().filter(i -> i.kind().equals("TEXT")).findFirst().orElseThrow().acceptedText());
        act(id,"cancel",0);act(id,"other",0);dispatch(id);answer(id,"موقع بديل");act(id,"done",0);act(id,"finish",0);act(id,"submit",0);
        assertEquals(1,service.report(id).sites().size());assertEquals("WORKED",service.report(id).outcome());
    }
    @Test void contextlessTextRequiresOneAcceptedCaptureAndConflictingContextNeverFallsBack() {
        String first=capture();var wrong=text("wamid.wrongText","موقع مرفوض","wamid.wrong");service.acceptVerifiedValue(wrong);assertEquals("OTHER_TEXT",store.request(first).step());
        ObjectNode malformed=(ObjectNode)text("wamid.malformedContext","موقع مرفوض",null);((ObjectNode)malformed.path("messages").get(0)).putObject("context");service.acceptVerifiedValue(malformed);assertEquals("OTHER_TEXT",store.request(first).step());
        clock.now=clock.now.plus(Duration.ofDays(1));String second=service.initiate(LocalDate.of(2026,10,23)).id();act(second,"start",0);act(second,"other",0);dispatch(second);
        service.acceptVerifiedValue(text("wamid.ambiguous","اسم ملتبس",null));assertEquals("OTHER_TEXT",store.request(first).step());assertEquals("OTHER_TEXT",store.request(second).step());
        assertTrue(store.inbox.values().stream().anyMatch(i -> i.disposition().equals("AMBIGUOUS")));
        answer(first,"الموقع الأقدم");assertEquals(LocalDate.of(2026,10,22),store.request(first).workDate());
        JsonNode contextless=text("wamid.contextless","الموقع الجديد",null);service.acceptVerifiedValue(contextless);service.acceptVerifiedValue(contextless);
        assertEquals(1,store.sites(second).size());
    }
    @Test void unknownAcceptanceDefersTextUntilContextIsProvenWithoutResending() {
        String id=initiate().id();act(id,"start",0);act(id,"other",0);
        service=newService(p -> new ReportingSender.Result("UNCERTAIN",null));dispatch(id);
        service.acceptVerifiedValue(text("wamid.deferredText","المبنى الشرقي","wamid.proven"));
        assertEquals("OTHER_TEXT",store.request(id).step());assertEquals("DEFERRED",store.inbox.values().stream().filter(i -> i.kind().equals("TEXT")).findFirst().orElseThrow().disposition());
        service.resolve(current(id).id(),"accepted","wamid.proven");assertEquals("COWORKERS",store.request(id).step());assertEquals(1,store.sites(id).size());
    }
    @Test void textCommitRollbackAndRecreationAndConcurrentRedeliveryCannotAdvanceTwice() throws Exception {
        String id=capture();JsonNode event=text("wamid.textRollback","مبنى تجريبي",current(id).metaId());
        store.failCommit=true;assertThrows(IllegalStateException.class,() -> service.acceptVerifiedValue(event));assertTrue(store.sites(id).isEmpty());
        service=newService(p -> new ReportingSender.Result("ACCEPTED","wamid.restarted"));
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a=pool.submit(() -> service.acceptVerifiedValue(event));var b=pool.submit(() -> service.acceptVerifiedValue(event));a.get();b.get();
        }
        assertEquals(1,store.sites(id).size());assertEquals("COWORKERS",store.request(id).step());
    }
    @Test void issuedNicknameIsSnapshotEvenIfNicknameChangesBeforeSelection() {
        store.choices.put(11L,new Choice(11,"اسم مختصر"));String id=initiate().id();act(id,"start",0);
        String shown=ReportingFlow.siteLabel(store.choices.get(11L));store.choices.put(11L,new Choice(11,"اسم معدل"));act(id,"site",11);
        assertEquals(shown,store.sites(id).getFirst().label());assertEquals(11L,store.sites(id).getFirst().siteId());
    }
    @Test void descriptionsAndPaginationAndConfirmationRespectBounds() {
        for(String invalid:List.of("a"," ","م".repeat(301),"اسم\nموقع","اسم\u202e"))assertNull(ReportingFlow.description(invalid));
        assertEquals("م".repeat(300),ReportingFlow.description("م".repeat(300)));
        for(long n=20;n<50;n++){store.choices.put(n,new Choice(n,"نفس الاسم الطويل جدا للاختبار"));store.activity.add(new MemoryReportingStore.Activity(7,n,LocalDate.of(2026,10,22)));}
        String id=initiate().id();act(id,"start",0);act(id,"page",0); // each page includes Other, navigation and finish within ten rows
        List<String> tokens=new ArrayList<>();collect(map(current(id).payload()),tokens);
        String next=tokens.stream().filter(t -> ReportingFlow.action(t).verb().equals("page")).findFirst().orElseThrow();dispatch(id);
        service.acceptVerifiedValue(value("wamid.page",next,current(id).metaId(),"list_reply"));
        JsonNode rows=map(current(id).payload()).path("interactive").path("action").path("sections").get(0).path("rows");assertTrue(rows.size()<=10);
        for(JsonNode row:rows)assertTrue(row.path("title").asText().codePointCount(0,row.path("title").asText().length())<=24);
    }
    @Test void fiftyEntriesRemainEnumeratedInBoundedConfirmationAndOtherIsUnavailableAtLimit() {
        String id=initiate().id();Request r=store.request(id);List<Site> sites=new ArrayList<>();
        for(long i=1;i<=50;i++)sites.add(new Site(id,i,null,"وصف طويل ".repeat(30),"وصف أصلي",null,null,null,null));
        ReportingFlow flow=new ReportingFlow(mapper);
        String confirmation=map(flow.prompt(r.advance("CONFIRM_WORK",null,"WORKED",clock.instant(),false),sites,List.of(),List.of(),List.of(),0)).path("interactive").path("body").path("text").asText();
        assertTrue(confirmation.length()<=1024);for(int i=1;i<=50;i++)assertTrue(confirmation.contains(i+". "));
        List<String> tokens=new ArrayList<>();collect(map(flow.prompt(r.advance("SITES",null,"WORKED",clock.instant(),false),sites,List.of(),List.of(),List.of(),0)),tokens);
        assertFalse(tokens.stream().anyMatch(t -> ReportingFlow.action(t).verb().equals("other")));
    }
}
