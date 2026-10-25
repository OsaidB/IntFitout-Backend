package life.work.IntFit.backend.reporting;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public final class ReportingModel {
    private ReportingModel() { }
    // These are persistence projections, not responses exposing existing worker entities.
    public record Worker(long id, String name, boolean eligible, String number) { }
    public record Choice(long id, String label) { }
    public record Request(String id, long workerId, String recipient, String phoneId, LocalDate workDate,
                          String step, long revision, Long currentSite, String outcome, Instant createdAt,
                          Instant lastInboundAt, Instant submittedAt) {
        public Request advance(String next, Long site, String result, Instant inbound, boolean submit) {
            return new Request(id, workerId, recipient, phoneId, workDate, next, revision + 1, site,
                    result, createdAt, inbound, submit ? inbound : null);
        }
    }
    /** entryId is per request, independent of the nullable original master siteId. */
    public record Site(String requestId, long entryId, Long siteId, String label, String originalDescription,
                       Long resolvedMasterId, String businessName, String resolutionKey, Instant resolvedAt) {
        public Site(String requestId,long siteId,String label) { this(requestId,siteId,siteId,label,null,null,null,null,null); }
    }
    public record Coworker(String requestId, long entryId, long workerId, String label) { }
    public record Inbox(String key, String messageId, String contextId, String kind, String action,
                        String requestId, String disposition, Instant receivedAt, Instant messageAt, String acceptedText) {
        public Inbox(String key,String messageId,String contextId,String kind,String action,String requestId,String disposition,Instant receivedAt,Instant messageAt) {
            this(key,messageId,contextId,kind,action,requestId,disposition,receivedAt,messageAt,null);
        }
        public Inbox disposition(String next) { return new Inbox(key,messageId,contextId,kind,action,requestId,next,receivedAt,messageAt,acceptedText); }
    }
    public record Outbound(String id, String requestId, long revision, String kind, String payload,
                           String state, String metaId, String delivery, Instant createdAt,
                           String recipientType,String recipientIdentity,String recipient,String phoneId,String runId,String failureCode) {
        public Outbound(String id,String requestId,long revision,String kind,String payload,String state,String metaId,String delivery,Instant createdAt) {
            this(id,requestId,revision,kind,payload,state,metaId,delivery,createdAt,"WORKER","WORKER",null,null,null,null);
        }
        public Outbound result(String next, String id, String status) {
            return new Outbound(this.id, requestId, revision, kind, payload, next, id, status, createdAt,recipientType,recipientIdentity,recipient,phoneId,runId,failureCode);
        }
        public Outbound blocked(String code) {return new Outbound(id,requestId,revision,kind,payload,"CANCELLED",null,null,createdAt,recipientType,recipientIdentity,recipient,phoneId,runId,code);}
        public Outbound routed(String to,String phone,String run) {return new Outbound(id,requestId,revision,kind,payload,state,metaId,delivery,createdAt,recipientType,recipientIdentity,to,phone,run,failureCode);}
    }
    public record OutboundView(String id, long revision, String kind, String state, String metaId, String delivery,String failureCode,String recipientIdentity) { }
    public record DailyRun(String id,String phoneId,LocalDate workDate,String phase,Instant createdAt,Instant cutoffAt,Instant expiresAt,String ownerRecipient,String fatherRecipient,String language,String failureCode,String summaryText,String summaryParameters) {
        public DailyRun phase(String phase,String failure) {return new DailyRun(id,phoneId,workDate,phase,createdAt,cutoffAt,expiresAt,ownerRecipient,fatherRecipient,language,failure,null,null);}
        public DailyRun snapshot(String text,String parameters) {return new DailyRun(id,phoneId,workDate,"SNAPSHOT",createdAt,cutoffAt,expiresAt,ownerRecipient,fatherRecipient,language,failureCode,text,parameters);}
    }
    public record DailyMember(String runId,long workerId,boolean configured,String exclusionCode,String requestId) { }
    public record ReportView(String id, long workerId, LocalDate workDate, String step, String outcome,
                             String reviewStatus, Instant submittedAt, List<Site> sites,
                             List<Coworker> coworkers, List<OutboundView> outbound, List<Inbox> inbox) { }
}
