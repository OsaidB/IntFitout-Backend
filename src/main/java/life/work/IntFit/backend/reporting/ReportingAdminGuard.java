package life.work.IntFit.backend.reporting;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;
import life.work.IntFit.backend.exception.error.AllocationValidationException;

/** Shared fail-closed administration credential. Deliberately does not represent a named person. */
public final class ReportingAdminGuard {
    private final ReportingSettings settings;
    public ReportingAdminGuard(ReportingSettings settings){this.settings=settings;}
    public ResponseEntity<?> execute(HttpServletRequest http,Set<String> query,Supplier<?> operation) {
        if(!settings.adminConfigured())return error(503,"Reporting administration credential is not configured.");
        var headers=Collections.list(http.getHeaders("X-WhatsApp-Reporting-Admin-Secret"));
        if(headers.size()!=1||!settings.authorized(headers.getFirst()))return error(403,"Reporting administration authorization rejected.");
        if(http.getParameterMap().entrySet().stream().anyMatch(e -> !query.contains(e.getKey())||e.getValue().length!=1))return error(400,"Unexpected or repeated query parameters.");
        try{return ResponseEntity.ok().header("Cache-Control","no-store").body(operation.get());}
        catch(ReportingProblem e){return error(e.status,e.getMessage());}
        catch(AllocationValidationException e){return ResponseEntity.unprocessableEntity().header("Cache-Control","no-store").body(Map.of("message",e.getMessage(),"errors",e.getErrors()));}
        catch(ResponseStatusException e){return error(e.getStatusCode().value(),e.getReason()==null?"Request rejected.":e.getReason());}
        catch(DuplicateKeyException e){return error(409,"A record already exists. Refresh persisted state before further action.");}
        catch(DataAccessException e){return error(503,"Reporting storage unavailable. Inspect persisted state before retrying.");}
        catch(RuntimeException e){return error(503,"Reporting operation could not be completed. Inspect persisted state before further action.");}
    }
    public static ResponseEntity<?> error(int status,String message){return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("message",message));}
}
