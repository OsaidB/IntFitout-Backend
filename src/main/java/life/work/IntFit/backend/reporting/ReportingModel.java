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
    public record Site(String requestId, long siteId, String label) { }
    public record Coworker(String requestId, long siteId, long workerId, String label) { }
    public record Inbox(String key, String messageId, String contextId, String kind, String action,
                        String requestId, String disposition, Instant receivedAt, Instant messageAt) {
        public Inbox disposition(String next) { return new Inbox(key,messageId,contextId,kind,action,requestId,next,receivedAt,messageAt); }
    }
    public record Outbound(String id, String requestId, long revision, String kind, String payload,
                           String state, String metaId, String delivery, Instant createdAt) {
        public Outbound result(String next, String id, String status) {
            return new Outbound(this.id, requestId, revision, kind, payload, next, id, status, createdAt);
        }
    }
    public record OutboundView(String id, long revision, String kind, String state, String metaId, String delivery) { }
    public record ReportView(String id, long workerId, LocalDate workDate, String step, String outcome,
                             String reviewStatus, Instant submittedAt, List<Site> sites,
                             List<Coworker> coworkers, List<OutboundView> outbound, List<Inbox> inbox) { }
}
