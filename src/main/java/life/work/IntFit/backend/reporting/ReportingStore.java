package life.work.IntFit.backend.reporting;

import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;
import static life.work.IntFit.backend.reporting.ReportingModel.*;

/** All mutations run in transaction(). Conversation/dispatch operations lock the pilot worker
 * before request/outbound rows, serializing pilot mutations. Network calls MUST be outside transactions. */
public interface ReportingStore {
    <T> T transaction(Supplier<T> action);
    Worker lockWorker(long id);
    void configureWorker(long id, boolean eligible, String number);
    boolean configureSite(long id, boolean selectable);
    List<Choice> selectableSites();
    List<Choice> coworkers(long exceptWorkerId);
    Request request(String id); // FOR UPDATE
    Request request(long workerId, LocalDate date);
    void insertRequest(Request request);
    void saveRequest(Request request);
    List<Request> requests(int limit);
    List<Site> sites(String requestId);
    void addSite(Site site);
    List<Coworker> mentions(String requestId);
    void addMention(Coworker coworker);
    void removeMention(String requestId, long siteId, long workerId);
    boolean hasInbox(String key);
    void insertInbox(Inbox event);
    List<Inbox> inboxFor(String requestId);
    void saveInbox(Inbox event);
    List<String> observedStatuses(String metaId);
    Outbound outbound(String id); // FOR UPDATE
    Outbound byMetaId(String id);
    List<Outbound> outboundFor(String requestId);
    void insertOutbound(Outbound outbound);
    void saveOutbound(Outbound outbound);
    List<String> pendingIds(int limit);
}
