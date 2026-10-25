package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import life.work.IntFit.backend.dto.MasterWorksiteDTO;
import life.work.IntFit.backend.service.MasterWorksiteService;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.HexFormat;
import static life.work.IntFit.backend.reporting.ReportingModel.*;

/** Administrator metadata only. Creation uses the same MasterWorksiteService and joins the store's
 * JPA transaction, so a failed link rolls back the new master as well. No worker answer is rewritten. */
@Service
public class ReportingResolutionService {
    private final ReportingStore store;
    private final MasterWorksiteService masters;
    private final Clock clock;
    private final ObjectMapper mapper;
    public ReportingResolutionService(ReportingStore store,MasterWorksiteService masters,Clock clock,ObjectMapper mapper) {
        this.store=store;this.masters=masters;this.clock=clock;this.mapper=mapper;
    }
    public Site resolve(String requestId,long entryId,Long masterId,MasterWorksiteDTO create) {
        try { if(!java.util.UUID.fromString(requestId).toString().equals(requestId)) throw new IllegalArgumentException(); }
        catch(RuntimeException e) { throw new ReportingProblem(400,"A canonical request UUID is required."); }
        if(entryId<=0 || (masterId==null)==(create==null) || (masterId!=null && masterId<=0))
            throw new ReportingProblem(400,"Provide exactly one existing masterWorksiteId or create definition.");
        if(create!=null) {
            if(create.getId()!=null) throw new ReportingProblem(400,"New master must not supply an ID.");
            try { create.setNickname(MasterWorksiteService.normalizeNickname(create.getNickname())); }
            catch(IllegalArgumentException e) { throw new ReportingProblem(400,"Invalid master nickname."); }
        }
        final String key=fingerprint(masterId,create);
        // Do the immutable owner lookup before starting the transaction. A consistent read before
        // waiting on the worker lock would pin an obsolete REPEATABLE READ snapshot on a retry.
        Long worker=store.requestWorkerId(requestId);
        if(worker==null) throw new ReportingProblem(404,"Reporting request not found.");
        return store.transaction(() -> {
            // Same lock order as inbound/dispatch; a preliminary read is never used to authorize mutations.
            store.lockWorker(worker);
            Request r=store.request(requestId);
            if(r==null || r.workerId()!=worker) throw new ReportingProblem(404,"Reporting request not found.");
            if(r.submittedAt()==null) throw new ReportingProblem(409,"Resolve Other entries after submission.");
            Site s=store.sites(requestId).stream().filter(x -> x.entryId()==entryId).findFirst().orElseThrow(() -> new ReportingProblem(404,"Report entry not found."));
            if(s.siteId()!=null) throw new ReportingProblem(409,"Only Other entries can be resolved.");
            if(s.resolvedMasterId()!=null) {
                if(!key.equals(s.resolutionKey())) throw new ReportingProblem(409,"Entry already has a different resolution.");
                return s;
            }
            MasterWorksiteDTO master;
            try { master=create==null ? masters.getById(masterId) : masters.add(create); }
            catch(IllegalArgumentException e) { throw new ReportingProblem(400,"Master creation rejected by existing business validation."); }
            catch(org.springframework.web.server.ResponseStatusException e) { throw new ReportingProblem(e.getStatusCode().value(),"Master worksite not found."); }
            store.resolveSite(requestId,entryId,master.getId(),key,clock.instant());
            return store.sites(requestId).stream().filter(x -> x.entryId()==entryId).findFirst().orElseThrow();
        });
    }
    private String fingerprint(Long masterId,MasterWorksiteDTO create) {
        try {
            byte[] body=(masterId==null ? "create:"+mapper.writeValueAsString(create) : "link:"+masterId).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch(Exception e) { throw new ReportingProblem(400,"Invalid resolution definition."); }
    }
}
