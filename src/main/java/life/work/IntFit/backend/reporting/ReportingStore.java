package life.work.IntFit.backend.reporting;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import static life.work.IntFit.backend.reporting.ReportingModel.*;

/** All mutations run in transaction(). Worker conversation/dispatch operations lock the actual request worker
 * before request/outbound rows; management operations lock their daily run first. Network calls MUST be outside transactions. */
public interface ReportingStore {
    <T> T transaction(Supplier<T> action);
    Worker lockWorker(long id);
    Worker inboundWorker(String recipient,String phoneId);
    List<Worker> activeWorkers(LocalDate date);
    void configureWorker(long id, boolean eligible, String number);
    boolean configureSite(long id, boolean selectable);
    List<Choice> selectableSites();
    boolean activeWorker(long workerId, LocalDate date);
    List<Choice> activeSites(LocalDate date);
    List<Request> textCaptures(long workerId);
    List<Choice> coworkers(long exceptWorkerId);
    Request request(String id); // FOR UPDATE
    Long requestWorkerId(String id); // Non-locking immutable owner lookup, before worker/request lock order.
    Request request(long workerId, LocalDate date);
    void insertRequest(Request request);
    void saveRequest(Request request);
    List<Request> requests(int limit);
    List<Site> sites(String requestId);
    void addSite(Site site);
    default boolean reviewExists(String requestId) { return false; }
    void resolveSite(String requestId,long entryId,long masterId,String key,java.time.Instant at);
    List<Coworker> mentions(String requestId);
    void addMention(Coworker coworker);
    void removeMention(String requestId, long siteId, long workerId);
    boolean hasInbox(String key);
    void insertInbox(Inbox event);
    List<Inbox> inboxFor(String requestId);
    void saveInbox(Inbox event);
    List<String> observedStatuses(String metaId);
    Outbound outbound(String id); // FOR UPDATE
    Outbound peekOutbound(String id);
    Outbound peekByMetaId(String id);
    List<Outbound> unboundOutbounds(String recipient,String phoneId);
    Outbound byMetaId(String id);
    List<Outbound> outboundFor(String requestId);
    void insertOutbound(Outbound outbound);
    void saveOutbound(Outbound outbound);
    List<String> pendingIds(int limit);
    DailyRun ensureRun(LocalDate date,String phoneId,java.time.Instant now,java.time.Instant cutoff,java.time.Instant expires,String owner,String father,String language);
    DailyRun runByDate(LocalDate date,String phoneId);
    DailyRun lockRun(String id);
    void saveRun(DailyRun run);
    void addRunMember(DailyMember member);
    List<DailyMember> runMembers(String id);
    void linkOpeningRun(String requestId,String runId);
    List<Outbound> managementOutbound(String runId);
}
