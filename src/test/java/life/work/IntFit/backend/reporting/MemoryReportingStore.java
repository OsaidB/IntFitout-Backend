package life.work.IntFit.backend.reporting;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import static life.work.IntFit.backend.reporting.ReportingModel.*;

/** Transactional FAKE only. Reusing this object across service instances simulates restart;
 * it does not verify JDBC, MySQL constraints/isolation, disk durability, or actual process restart. */
public final class MemoryReportingStore implements ReportingStore {
    final Map<Long,Worker> workers=new LinkedHashMap<>();
    final Map<Long,Choice> choices=new LinkedHashMap<>();
    final Set<Long> selectable=new HashSet<>();
    record Activity(long worker,long site,LocalDate date) { }
    final List<Activity> activity=new ArrayList<>();
    Map<String,Request> requests=new LinkedHashMap<>();
    Map<String,Site> sites=new LinkedHashMap<>();
    Map<String,Coworker> mentions=new LinkedHashMap<>();
    Map<String,Inbox> inbox=new LinkedHashMap<>();
    Map<String,Outbound> outbound=new LinkedHashMap<>();
    boolean failCommit;
    int transactions;
    @Override public synchronized <T> T transaction(Supplier<T> action) {
        var r=new LinkedHashMap<>(requests); var s=new LinkedHashMap<>(sites); var c=new LinkedHashMap<>(mentions);
        var i=new LinkedHashMap<>(inbox); var o=new LinkedHashMap<>(outbound);
        var w=new LinkedHashMap<>(workers); var selection=new HashSet<>(selectable);
        transactions++;
        try {
            T result=action.get();
            if (failCommit) { failCommit=false; throw new IllegalStateException("simulated commit interruption"); }
            return result;
        } catch (RuntimeException | Error e) {
            requests=r; sites=s; mentions=c; inbox=i; outbound=o;
            workers.clear();workers.putAll(w);selectable.clear();selectable.addAll(selection);
            throw e;
        }
    }
    @Override public Worker lockWorker(long id) { return workers.get(id); }
    @Override public void configureWorker(long id,boolean eligible,String number) {
        if (number!=null && workers.values().stream().anyMatch(w -> w.id()!=id && number.equals(w.number())))
            throw new org.springframework.dao.DuplicateKeyException("synthetic duplicate");
        Worker w=workers.get(id);workers.put(id,new Worker(id,w.name(),eligible,number));
    }
    @Override public boolean configureSite(long id,boolean enabled) { if (!choices.containsKey(id)) return false; if(enabled) selectable.add(id); else selectable.remove(id);return true; }
    @Override public List<Choice> selectableSites() { return choices.values().stream().filter(c -> selectable.contains(c.id())).toList(); }
    @Override public boolean activeWorker(long id,LocalDate date) { return activity.stream().anyMatch(a -> a.worker()==id && !a.date().isBefore(date.minusDays(6)) && !a.date().isAfter(date)); }
    @Override public List<Choice> activeSites(LocalDate date) { return choices.values().stream().filter(c -> activity.stream().anyMatch(a -> a.site()==c.id() && !a.date().isBefore(date.minusDays(6)) && !a.date().isAfter(date))).toList(); }
    @Override public List<Request> textCaptures(long worker) { return requests.values().stream().filter(r -> r.workerId()==worker && r.step().equals("OTHER_TEXT") && r.submittedAt()==null).toList(); }
    @Override public List<Choice> coworkers(long except) { return workers.values().stream().filter(w -> w.id()!=except && w.eligible()).map(w -> new Choice(w.id(),w.name())).toList(); }
    @Override public Request request(String id) { return requests.get(id); }
    @Override public Long requestWorkerId(String id) { Request r=request(id);return r==null ? null : r.workerId(); }
    @Override public Request request(long worker,LocalDate date) { return requests.values().stream().filter(r -> r.workerId()==worker && r.workDate().equals(date)).findFirst().orElse(null); }
    @Override public void insertRequest(Request r) { if(request(r.workerId(),r.workDate())!=null) throw new AssertionError("duplicate request"); requests.put(r.id(),r); }
    @Override public void saveRequest(Request r) { if(requests.get(r.id()).submittedAt()!=null) throw new AssertionError("immutable submission"); requests.put(r.id(),r); }
    @Override public List<Request> requests(int limit) { return requests.values().stream().limit(limit).toList(); }
    @Override public List<Site> sites(String id) { return sites.values().stream().filter(s -> s.requestId().equals(id)).toList(); }
    @Override public void addSite(Site s) { String key=s.requestId()+":"+s.entryId();if(sites.putIfAbsent(key,s)!=null) throw new AssertionError("duplicate site"); }
    @Override public void resolveSite(String request,long entry,long master,String key,java.time.Instant at) {
        Site s=sites.get(request+":"+entry);
        sites.put(request+":"+entry,new Site(request,entry,null,s.label(),s.originalDescription(),master,choices.get(master).label(),key,at));
    }
    @Override public List<Coworker> mentions(String id) { return mentions.values().stream().filter(c -> c.requestId().equals(id)).toList(); }
    @Override public void addMention(Coworker c) { mentions.put(c.requestId()+":"+c.entryId()+":"+c.workerId(),c); }
    @Override public void removeMention(String r,long s,long w) { mentions.remove(r+":"+s+":"+w); }
    @Override public boolean hasInbox(String key) { return inbox.containsKey(key); }
    @Override public void insertInbox(Inbox i) { if(inbox.putIfAbsent(i.key(),i)!=null) throw new AssertionError("duplicate inbox"); }
    @Override public List<Inbox> inboxFor(String id) { return inbox.values().stream().filter(i -> id.equals(i.requestId())).toList(); }
    @Override public void saveInbox(Inbox i) { inbox.put(i.key(),i); }
    @Override public List<String> observedStatuses(String id) { return inbox.values().stream().filter(i -> i.messageId().equals(id) && i.kind().startsWith("STATUS_")).map(Inbox::kind).toList(); }
    @Override public Outbound outbound(String id) { return outbound.get(id); }
    @Override public Outbound byMetaId(String id) { return outbound.values().stream().filter(o -> id.equals(o.metaId())).findFirst().orElse(null); }
    @Override public List<Outbound> outboundFor(String id) { return outbound.values().stream().filter(o -> o.requestId().equals(id)).toList(); }
    @Override public void insertOutbound(Outbound o) { if(outbound.values().stream().anyMatch(x -> x.requestId().equals(o.requestId()) && x.revision()==o.revision())) throw new AssertionError("duplicate revision"); outbound.put(o.id(),o); }
    @Override public void saveOutbound(Outbound o) { outbound.put(o.id(),o); }
    @Override public List<String> pendingIds(int limit) { return outbound.values().stream().filter(o -> o.state().equals("PENDING")).limit(limit).map(Outbound::id).toList(); }
}
