package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.util.*;
import static life.work.IntFit.backend.reporting.ReportingModel.*;

/** Reporting mutations only. Assignment activity is read through the store; never saved here. */
@Service
public class ReportingService {
    private static final Logger log=LoggerFactory.getLogger(ReportingService.class);
    private static final ZoneId ZONE=ZoneId.of("Asia/Hebron");
    private final ReportingStore store;
    private final ReportingSettings settings;
    private final ReportingSender sender;
    private final Clock clock;
    private final ReportingFlow flow;

    public ReportingService(ReportingStore store,ReportingSettings settings,ReportingSender sender,Clock clock,ObjectMapper mapper) {
        this.store=store; this.settings=settings; this.sender=sender; this.clock=clock; flow=new ReportingFlow(mapper);
    }
    public Map<String,Object> status() {
        return Map.of("reportingEnabled",settings.enabled,"sendingEnabled",settings.sendEnabled,
                "pilotConfigurationReady",settings.receivingReady(),"sendingConfigurationReady",settings.sendingReady(),
                "automaticDailyRequests",settings.dailyEnabled,"rolloutEnabled",settings.rolloutEnabled,"dailyConfigurationReady",settings.dailyReady());
    }
    public void configureWorker(long id,boolean eligible,String suppliedNumber) {
        String number=ReportingSettings.normalizeNumber(suppliedNumber);
        if (eligible && number==null) throw new ReportingProblem(400,"Eligibility requires an explicit international WhatsApp number.");
        store.transaction(() -> {
            if (store.lockWorker(id)==null) throw new ReportingProblem(404,"Worker not found.");
            store.configureWorker(id,eligible,number); return null;
        });
    }
    public void configureSite(long id,boolean selectable) {
        store.transaction(() -> {
            if (!store.configureSite(id,selectable)) throw new ReportingProblem(404,"Master worksite not found.");
            return null;
        });
    }
    public ReportView initiate(LocalDate date) {
        requireSending();
        if (date==null || !date.equals(LocalDate.now(clock.withZone(ZONE))))
            throw new ReportingProblem(400,"Pilot initiation requires today's Asia/Hebron work date.");
        if(!settings.pilotReady()) throw new ReportingProblem(409,"Explicit pilot identity is required for manual pilot initiation.");
        return store.transaction(() -> {
            Worker worker=store.lockWorker(settings.workerId);requireWorker(worker);
            if(!settings.recipient.equals(worker.number())) throw new ReportingProblem(409,"Manual pilot recipient must match its explicitly configured worker number.");
            return view(ensureRequest(settings.workerId,date,null));
        });
    }
    // Called within the daily run transaction; no caller-supplied recipients or message overrides.
    Request ensureRequest(long workerId,LocalDate date,String runId) {
        Worker worker=store.lockWorker(workerId); requireWorker(worker);
        Request existing=store.request(workerId,date);
        if(existing!=null) {
            if(!matchesRequest(worker,existing)) throw new ReportingProblem(409,"Existing request routing snapshots differ from current worker/sender setup.");
            if(runId!=null) store.linkOpeningRun(existing.id(),runId);
            return existing;
        }
        requireActivity(workerId,date);
        Request r=new Request(UUID.randomUUID().toString(),workerId,worker.number(),settings.phoneId,date,
                "START",0,null,null,clock.instant(),null,null);
        store.insertRequest(r);
        store.insertOutbound(new Outbound(UUID.randomUUID().toString(),r.id(),0,"TEMPLATE",flow.template(r,settings.language),"PENDING",null,null,clock.instant()).routed(r.recipient(),r.phoneId(),runId));
        return r;
    }
    public List<ReportView> reports(int limit) {
        if (limit<1 || limit>100) throw new ReportingProblem(400,"Limit must be between 1 and 100.");
        return store.transaction(() -> store.requests(limit).stream().map(this::view).toList());
    }
    public ReportView report(String id) {
        validUuid(id);
        Long owner=store.requestWorkerId(id);
        return store.transaction(() -> {
            if(owner!=null) store.lockWorker(owner);
            Request r=store.request(id);
            if (r==null) throw new ReportingProblem(404,"Reporting request not found.");
            return view(r);
        });
    }
    private ReportView view(Request r) {
        return new ReportView(r.id(),r.workerId(),r.workDate(),r.step(),r.outcome(),r.submittedAt()==null ? "DRAFT" : "AWAITING_REVIEW",
                r.submittedAt(),store.sites(r.id()),store.mentions(r.id()),store.outboundFor(r.id()).stream()
                .map(o -> outboundView(o)).toList(),store.inboxFor(r.id()));
    }

    /** Called ONLY after the controller has authenticated the original HTTP bytes. One transaction per event.
     * If any transaction fails, propagate to the controller for a sanitized 503; Meta may redeliver the batch. */
    public void acceptVerifiedValue(JsonNode value) {
        if (!settings.enabled || !Objects.equals(settings.phoneId,value.path("metadata").path("phone_number_id").asText())) return;
        for (JsonNode m:array(value.path("messages"))) {
            String from=m.path("from").asText();
            if(!settings.receivingReady() || !ReportingSettings.canonicalNumber(from) || settings.managementNumberMatches(from)) continue;
            Worker worker=store.inboundWorker(from,settings.phoneId);
            if(worker!=null && settings.allowsWorker(worker)) receiveMessage(m,worker.id());
        }
        for (JsonNode status:array(value.path("statuses"))) if(status.isObject()) receiveStatus(status);
    }
    private void receiveMessage(JsonNode m,long workerId) {
        String messageId=externalId(m.path("id").asText());
        if (messageId==null) return;
        if ("text".equals(m.path("type").asText())) { receiveText(m,messageId,workerId); return; }
        String context=externalId(m.path("context").path("id").asText());
        String actionValue=actionValue(m);
        ReportingFlow.Action action=ReportingFlow.action(actionValue);
        Instant sentAt=messageTime(m.path("timestamp").asText());
        String key=hash("message:"+settings.phoneId+":"+messageId);
        store.transaction(() -> {
            Worker worker=store.lockWorker(workerId);
            if (store.hasInbox(key)) return null;
            String disposition="IGNORED";
            Request r=action==null ? null : store.request(action.requestId());
            if (r!=null && sentAt!=null && matchesRequest(worker,r)
                    && r.submittedAt()==null && r.revision()==action.revision()) {
                Outbound prompt=store.outboundFor(r.id()).stream().filter(o -> o.revision()==r.revision()).findFirst().orElse(null);
                if (prompt!=null && context!=null && flow.issued(prompt.payload(),actionValue)) {
                    if ("ACCEPTED".equals(prompt.state()) && context.equals(prompt.metaId()))
                        disposition=advance(r,action,sentAt,prompt.payload()) ? "APPLIED" : "IGNORED";
                    else if (Set.of("SENDING","UNCERTAIN").contains(prompt.state()) && prompt.metaId()==null)
                        disposition="DEFERRED"; // Validate the context only after the uncertain Meta ID is resolved.
                }
            }
            store.insertInbox(new Inbox(key,messageId,context,"MESSAGE",action==null ? null : actionValue,
                    r==null ? null : r.id(),disposition,clock.instant(),sentAt));
            return null;
        });
    }
    private boolean advance(Request r,ReportingFlow.Action a,Instant received,String issuedPayload) {
        String next=null, outcome=r.outcome(); Long current=r.currentSite(); int page=0;
        List<Site> sites=store.sites(r.id());
        switch (r.step()) {
            case "START" -> {
                if (a.verb().equals("start")) { next="SITES"; outcome="WORKED"; }
                if (a.verb().equals("none")) { next="CONFIRM_NONE"; outcome="DID_NOT_WORK"; }
            }
            case "SITES" -> {
                if (a.verb().equals("site") && sites.size()<50 && sites.stream().noneMatch(s -> Objects.equals(s.siteId(),a.id()))) {
                    Choice choice=store.activeSites(r.workDate()).stream().filter(c -> c.id()==a.id()).findFirst().orElse(null);
                    if (choice!=null) {
                        current=nextEntry(sites);
                        String shown=flow.issuedLabel(issuedPayload,ReportingFlow.token(r,"site",a.id(),a.page()));
                        store.addSite(new Site(r.id(),current,choice.id(),shown==null ? ReportingFlow.siteLabel(choice) : shown,null,null,null,null,null)); next="COWORKERS";
                    }
                }
                if (a.verb().equals("other") && sites.size()<50) { next="OTHER_TEXT"; current=null; }
                if (a.verb().equals("finish") && !sites.isEmpty()) { next="CONFIRM_WORK"; current=null; }
                if (a.verb().equals("page")) { next="SITES"; page=a.page(); }
            }
            case "OTHER_TEXT" -> { if (a.verb().equals("cancel")) { next="SITES"; current=null; } }
            case "COWORKERS" -> {
                if (a.verb().equals("cow") && current!=null) {
                    Choice choice=store.coworkers(r.workerId()).stream().filter(c -> c.id()==a.id()).findFirst().orElse(null);
                    if (choice!=null) {
                        final long siteId=current;
                        boolean exists=store.mentions(r.id()).stream().anyMatch(c -> c.entryId()==siteId && c.workerId()==choice.id());
                        if (exists) store.removeMention(r.id(),siteId,choice.id());
                        else store.addMention(new Coworker(r.id(),siteId,choice.id(),ReportingFlow.label(choice.label(),255)));
                        next="COWORKERS"; page=a.page();
                    }
                }
                if (a.verb().equals("done")) { next="SITES"; current=null; }
                if (a.verb().equals("page")) { next="COWORKERS"; page=a.page(); }
            }
            case "CONFIRM_WORK", "CONFIRM_NONE" -> {
                if (a.verb().equals("submit")) next="SUBMITTED";
                if (a.verb().equals("back")) { next=r.step().equals("CONFIRM_NONE") ? "START" : "SITES"; current=null; outcome=next.equals("START") ? null : "WORKED"; }
            }
            default -> { return false; }
        }
        if (next==null) return false;
        Instant last=r.lastInboundAt()!=null && r.lastInboundAt().isAfter(received) ? r.lastInboundAt() : received;
        Request updated=r.advance(next,current,outcome,last,next.equals("SUBMITTED"));
        store.saveRequest(updated);
        prompt(updated,page);
        return true;
    }
    private static long nextEntry(List<Site> sites) { return sites.stream().mapToLong(Site::entryId).max().orElse(0)+1; }
    private void prompt(Request r,int page) {
        intent(r,"PROMPT",flow.prompt(r,store.sites(r.id()),store.mentions(r.id()),store.activeSites(r.workDate()),store.coworkers(r.workerId()),page));
    }
    /** Text is never assigned to the latest phone request. Lock the pilot, then inspect persisted captures.
     * Without context, exactly one capture with an accepted prompt is required. Conflicting supplied
     * context is never discarded. Uncertain contextual replies defer until evidenced Meta binding. */
    private void receiveText(JsonNode m,String messageId,long workerId) {
        boolean contextProvided=m.has("context");
        String supplied=m.path("context").path("id").asText(null);
        String context=externalId(supplied);
        String body=m.path("text").path("body").isTextual() ? m.path("text").path("body").textValue() : null;
        String accepted=ReportingFlow.description(body);
        Instant sentAt=messageTime(m.path("timestamp").asText());
        String key=hash("message:"+settings.phoneId+":"+messageId);
        store.transaction(() -> {
            Worker worker=store.lockWorker(workerId);
            if (store.hasInbox(key)) return null;
            List<Request> captures=store.textCaptures(workerId).stream().filter(r -> matchesRequest(worker,r)).toList();
            List<Request> candidates=captures.stream().filter(r -> {
                Outbound p=currentPrompt(r);
                return p!=null && ((context!=null && context.equals(p.metaId())) ||
                        (captures.size()==1 && p.metaId()==null && Set.of("SENDING","UNCERTAIN").contains(p.state())));
            }).toList();
            if (!contextProvided) candidates=captures.size()==1 ? captures : List.of();
            if (contextProvided && context==null) candidates=List.of();
            Request r=candidates.size()==1 ? candidates.getFirst() : null;
            String disposition=captures.size()>1 && !contextProvided ? "AMBIGUOUS" : "IGNORED";
            String action=null,storedText=null;
            if (r!=null && workerAllowed(worker) && sentAt!=null) {
                Outbound p=currentPrompt(r);
                // Seconds are Meta's timestamp precision; do not reject same-second replies.
                if (p!=null && !sentAt.isBefore(p.createdAt().truncatedTo(java.time.temporal.ChronoUnit.SECONDS))) {
                    action=ReportingFlow.token(r,"text",0,0);
                    if ("ACCEPTED".equals(p.state()) && (!contextProvided || context.equals(p.metaId()))) {
                        applyText(r,accepted,sentAt);
                        disposition=accepted==null ? "INVALID_TEXT" : "APPLIED"; storedText=accepted;
                    } else if (context!=null && accepted!=null && p.metaId()==null && Set.of("SENDING","UNCERTAIN").contains(p.state())) {
                        disposition="DEFERRED"; storedText=accepted;
                    }
                }
            }
            store.insertInbox(new Inbox(key,messageId,context,"TEXT",action,r==null ? null : r.id(),disposition,clock.instant(),sentAt,storedText));
            return null;
        });
    }
    private Outbound currentPrompt(Request r) { return store.outboundFor(r.id()).stream().filter(o -> o.revision()==r.revision()).findFirst().orElse(null); }
    private void applyText(Request r,String description,Instant at) {
        Long entry=null;
        if(description!=null) {
            entry=nextEntry(store.sites(r.id()));
            store.addSite(new Site(r.id(),entry,null,ReportingFlow.label(description,255),description,null,null,null,null));
        }
        Instant last=r.lastInboundAt()!=null && r.lastInboundAt().isAfter(at) ? r.lastInboundAt() : at;
        Request updated=r.advance(description==null ? "OTHER_TEXT" : "COWORKERS",entry,r.outcome(),last,false);
        store.saveRequest(updated);prompt(updated,0);
    }
    private void receiveStatus(JsonNode status) {
        String metaId=externalId(status.path("id").asText());String recipient=status.path("recipient_id").asText();
        String state=status.path("status").asText();
        if(metaId==null || !ReportingSettings.canonicalNumber(recipient) || !Set.of("sent","delivered","read","failed").contains(state)) return;
        Outbound target=store.peekByMetaId(metaId);
        if(target==null) {
            List<Outbound> unbound=store.unboundOutbounds(recipient,settings.phoneId);
            if(unbound.size()!=1) return; // Never attach arbitrary statuses to the latest report.
            target=unbound.getFirst();
        }
        if(!recipient.equals(target.recipient()) || !Objects.equals(settings.phoneId,target.phoneId())) return;
        Routing route=routing(target);
        String stamp=status.path("timestamp").asText("");if(!stamp.matches("[0-9]{1,12}")) stamp="";
        String key=hash("status:"+settings.phoneId+":"+metaId+":"+state+":"+stamp);
        store.transaction(() -> {
            lockOwner(route);
            if(store.hasInbox(key)) return null;
            Outbound current=store.outbound(route.outbound().id());
            if(current==null || !recipient.equals(current.recipient()) || !Objects.equals(settings.phoneId,current.phoneId())) return null;
            if(current.metaId()!=null && !metaId.equals(current.metaId())) return null;
            if(current.metaId()==null && !Set.of("SENDING","UNCERTAIN").contains(current.state())) return null;
            store.insertInbox(new Inbox(key,metaId,null,"STATUS_"+state.toUpperCase(Locale.ROOT),null,current.requestId(),current.metaId()==null ? "UNMATCHED" : "RECORDED",clock.instant(),null));
            if(metaId.equals(current.metaId())) store.saveOutbound(current.result(current.state(),current.metaId(),delivery(metaId)));
            return null;
        });
    }
    private String delivery(String id) {
        List<String> statuses=store.observedStatuses(id);
        for (String state:List.of("READ","DELIVERED","FAILED","SENT")) if (statuses.contains("STATUS_"+state)) return state;
        return null;
    }
    private void intent(Request r,String kind,String payload) {
        store.insertOutbound(new Outbound(UUID.randomUUID().toString(),r.id(),r.revision(),kind,payload,"PENDING",null,null,clock.instant()).routed(r.recipient(),r.phoneId(),null));
    }

    /** Claim commits BEFORE HTTP. SENDING/UNCERTAIN/REJECTED/ACCEPTED are never automatically retried. */
    public OutboundView dispatch(String id) {
        validUuid(id);
        if(!settings.transportReady()) throw new ReportingProblem(503,"Reporting sending is paused or transport configuration is incomplete.");
        Outbound original=store.peekOutbound(id);
        if(original==null) throw new ReportingProblem(404,"Outbound intent not found.");
        Routing route=routing(original);
        Claim claim=store.transaction(() -> {
            Worker worker=lockOwner(route);
            Outbound o=store.outbound(id);
            if(!"PENDING".equals(o.state())) return new Claim(o,false);
            String blocked=null;
            if(!Objects.equals(settings.phoneId,o.phoneId())) blocked="SENDER_CHANGED";
            if(o.recipientType().equals("MANAGEMENT")) {
                DailyRun run=store.lockRun(o.runId());
                if(!settings.dailyEnabled || !settings.managementReady() || !Objects.equals(settings.managementRecipients().get(o.recipientIdentity()),o.recipient())) blocked="MANAGEMENT_SETUP_CHANGED";
                if(run==null || !run.workDate().equals(LocalDate.now(clock.withZone(ZONE))) || !clock.instant().isBefore(run.expiresAt())) blocked="SUMMARY_EXPIRED";
                else if(!run.phase().equals("SNAPSHOT") || !Objects.equals(settings.managementLanguage,run.language())) blocked="MANAGEMENT_SETUP_CHANGED";
            } else {
                Request r=store.request(o.requestId());
                if(!settings.sendingReady() || !matchesRequest(worker,r) || !Objects.equals(o.recipient(),r.recipient())) blocked="WORKER_SETUP_CHANGED";
                if(r!=null && o.kind().equals("TEMPLATE")) {
                    if(!store.activeWorker(r.workerId(),r.workDate())) blocked="NO_RECENT_ACTIVITY";
                    if(!r.workDate().equals(LocalDate.now(clock.withZone(ZONE)))) blocked="OPENING_EXPIRED";
                    if(o.runId()!=null) {
                        DailyRun run=store.runByDate(r.workDate(),r.phoneId());
                        if(!settings.dailyReady() || run==null || !clock.instant().isBefore(run.cutoffAt())) blocked="DAILY_OPENING_CLOSED";
                    }
                }
                if(r!=null && (o.revision()!=r.revision() || (!o.kind().equals("TEMPLATE") && (r.lastInboundAt()==null || !r.lastInboundAt().isAfter(clock.instant().minus(Duration.ofHours(24))))))) blocked="PROMPT_EXPIRED";
            }
            Outbound changed=blocked==null ? o.result("SENDING",null,null) : o.blocked(blocked);
            store.saveOutbound(changed);return new Claim(changed,blocked==null);
        });
        return claim.claimed() ? finishClaim(claim.outbound()) : outboundView(claim.outbound());
    }
    private record Routing(Outbound outbound,Long workerId) { }
    private Routing routing(Outbound o) {return new Routing(o,o.requestId()==null ? null : store.requestWorkerId(o.requestId()));}
    private Worker lockOwner(Routing route) {
        if(route.outbound().recipientType().equals("MANAGEMENT")) {store.lockRun(route.outbound().runId());return null;}
        return route.workerId()==null ? null : store.lockWorker(route.workerId());
    }
    private record Claim(Outbound outbound,boolean claimed) { }
    private OutboundView finishClaim(Outbound claim) {
        ReportingSender.Result result;
        try { result=sender.send(claim); }
        catch (RuntimeException ignored) { result=new ReportingSender.Result("UNCERTAIN",null); }
        if (result==null || result.state()==null || !Set.of("ACCEPTED","REJECTED","UNCERTAIN").contains(result.state())
                || (result.state().equals("ACCEPTED") && !MetaReportingSender.safeId(result.metaId(),settings)))
            result=new ReportingSender.Result("UNCERTAIN",null);
        final ReportingSender.Result outcome=result;
        Routing route=routing(claim);
        Outbound finished=store.transaction(() -> {
            lockOwner(route);
            Outbound current=store.outbound(claim.id());
            // A recovery operator can close a stranded SENDING attempt. Never overwrite that decision.
            if (!current.state().equals("SENDING")) return current;
            Outbound updated=current.result(outcome.state(),outcome.metaId(),outcome.metaId()==null ? null : delivery(outcome.metaId()));
            store.saveOutbound(updated);
            if (updated.state().equals("ACCEPTED") && updated.requestId()!=null) replayDeferred(updated.requestId());
            return updated;
        });
        log.info("whatsapp_reporting outbound={} result={}",claim.id(),finished.state());
        return outboundView(finished);
    }
    public void drainOne() {
        if(!settings.anySendingReady()) return;
        // A blocked individual becomes visibly CANCELLED; continue to a valid item this pass.
        for(String id:store.pendingIds(50)) {
            OutboundView result=dispatch(id);
            if(!"CANCELLED".equals(result.state())) break;
        }
    }
    /** Evidence-based reconciliation only. No operation resets an attempted message to PENDING. */
    public OutboundView resolve(String id,String decision,String metaId) {
        validUuid(id);
        if (!Set.of("accepted","abandon").contains(decision==null ? "" : decision)) throw new ReportingProblem(400,"Decision must be accepted or abandon.");
        if (decision.equals("accepted") && (!settings.enabled || settings.phoneId==null || !MetaReportingSender.safeId(metaId,settings)))
            throw new ReportingProblem(400,"Accepted reconciliation requires a validated Meta message ID and a configured receiving business sender.");
        Outbound original=store.peekOutbound(id);
        if(original==null) throw new ReportingProblem(404,"Outbound intent not found.");
        Routing route=routing(original);
        return store.transaction(() -> {
            lockOwner(route);
            Outbound o=store.outbound(id);
            if (o==null) throw new ReportingProblem(404,"Outbound intent not found.");
            if (decision.equals("accepted") && !Objects.equals(settings.phoneId,o.phoneId()))
                throw new ReportingProblem(409,"Reconciliation must match the configured receiving business sender.");
            if (!(Set.of("SENDING","UNCERTAIN").contains(o.state()) || (decision.equals("abandon") && o.state().equals("PENDING"))))
                throw new ReportingProblem(409,"Only pending abandonment or interrupted/uncertain reconciliation is allowed.");
            Outbound updated=o.result(decision.equals("accepted") ? "ACCEPTED" : "CANCELLED",
                    decision.equals("accepted") ? metaId : null,decision.equals("accepted") ? delivery(metaId) : null);
            store.saveOutbound(updated);
            if (updated.state().equals("ACCEPTED") && updated.requestId()!=null) replayDeferred(updated.requestId());
            else if(updated.requestId()!=null) for (Inbox i:store.inboxFor(updated.requestId())) {
                ReportingFlow.Action a=ReportingFlow.action(i.action());
                if (i.disposition().equals("DEFERRED") && a!=null && a.revision()==updated.revision()) store.saveInbox(i.disposition("IGNORED"));
            }
            return outboundView(updated);
        });
    }
    private void replayDeferred(String requestId) {
        Long owner=store.requestWorkerId(requestId);
        Worker worker=owner==null ? null : store.lockWorker(owner);
        for (Inbox i:store.inboxFor(requestId)) {
            if (!i.disposition().equals("DEFERRED")) continue;
            Request r=store.request(requestId);
            ReportingFlow.Action a=ReportingFlow.action(i.action());
            String disposition="IGNORED";
            if (a!=null && r!=null && r.submittedAt()==null && r.revision()==a.revision() && i.messageAt()!=null
                    && matchesRequest(worker,r)) {
                Outbound prompt=store.outboundFor(requestId).stream().filter(o -> o.revision()==r.revision()).findFirst().orElse(null);
                if (prompt!=null && "ACCEPTED".equals(prompt.state()) && Objects.equals(prompt.metaId(),i.contextId())) {
                    if (i.kind().equals("TEXT") && r.step().equals("OTHER_TEXT") && i.acceptedText()!=null) {
                        applyText(r,i.acceptedText(),i.messageAt()); disposition="APPLIED";
                    } else if (flow.issued(prompt.payload(),i.action())) disposition=advance(r,a,i.messageAt(),prompt.payload()) ? "APPLIED" : "IGNORED";
                }
            }
            store.saveInbox(i.disposition(disposition));
        }
    }
    private void requireSending() { if (!settings.sendingReady()) throw new ReportingProblem(503,"Reporting worker sending is paused or sender/template/rollout setup is incomplete."); }
    private boolean workerAllowed(Worker w) {
        return settings.allowsWorker(w);
    }
    private void requireWorker(Worker w) { if (!workerAllowed(w)) throw new ReportingProblem(409,"Explicit worker participation, normalized number and reviewed rollout scope are required."); }
    private void requireActivity(long worker,LocalDate date) {
        if(!store.activeWorker(worker,date)) throw new ReportingProblem(409,"Opening reports require a saved assignment between workDate minus six days and workDate, inclusive.");
    }
    static OutboundView outboundView(Outbound o) { return new OutboundView(o.id(),o.revision(),o.kind(),o.state(),o.metaId(),o.delivery(),o.failureCode(),o.recipientIdentity()); }
    private boolean matchesRequest(Worker w,Request r) {return r!=null && settings.allowsWorker(w) && r.workerId()==w.id() && Objects.equals(w.number(),r.recipient()) && Objects.equals(settings.phoneId,r.phoneId());}
    private static Iterable<JsonNode> array(JsonNode node) { return node.isArray() ? node : List.of(); }
    private static String actionValue(JsonNode m) {
        String type=m.path("type").asText();
        String value="";
        if (type.equals("button")) value=m.path("button").path("payload").asText();
        if (type.equals("interactive")) {
            JsonNode i=m.path("interactive");
            if (i.path("type").asText().equals("button_reply")) value=i.path("button_reply").path("id").asText();
            if (i.path("type").asText().equals("list_reply")) value=i.path("list_reply").path("id").asText();
        }
        return value.length()<=200 ? value : "";
    }
    static String externalId(String v) { return v!=null && v.matches("wamid\\.[A-Za-z0-9._:+/=-]{1,512}") ? v : null; }
    private Instant messageTime(String value) {
        try {
            if (!value.matches("[0-9]{1,12}")) return null;
            Instant time=Instant.ofEpochSecond(Long.parseLong(value));
            if (time.isAfter(clock.instant().plusSeconds(300))) return null;
            return time.isAfter(clock.instant()) ? clock.instant() : time;
        } catch (RuntimeException ignored) { return null; }
    }
    private static void validUuid(String id) {
        try { if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException(); }
        catch (RuntimeException ignored) { throw new ReportingProblem(400,"A canonical request/outbound UUID is required."); }
    }
    private static String hash(String s) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
    }
}
