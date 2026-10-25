package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/** Dedicated credential on EVERY route. Never rely on legacy application authentication/CORS. */
@RestController
@RequestMapping("/api/whatsapp/reporting/admin")
public class ReportingAdminController {
    private final ReportingSettings settings;
    private final ReportingService service;
    private final ReportingResolutionService resolutions;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper=new com.fasterxml.jackson.databind.ObjectMapper();
    public ReportingAdminController(ReportingSettings settings,ReportingService service) { this(settings,service,null); }
    @org.springframework.beans.factory.annotation.Autowired
    public ReportingAdminController(ReportingSettings settings,ReportingService service,ReportingResolutionService resolutions) { this.settings=settings; this.service=service;this.resolutions=resolutions; }
    @GetMapping("/status") public ResponseEntity<?> status(HttpServletRequest http) {
        return guarded(http,service::status);
    }
    @PutMapping("/workers/{id}") public ResponseEntity<?> worker(HttpServletRequest http,@PathVariable long id,@RequestBody JsonNode body) {
        return guarded(http,() -> {
            fields(body,Set.of("eligible","whatsappNumber"));
            if (!body.path("eligible").isBoolean() || !(body.path("whatsappNumber").isTextual() || body.path("whatsappNumber").isNull()))
                throw new ReportingProblem(400,"Provide eligible (boolean) and whatsappNumber (international string or null).");
            service.configureWorker(id,body.path("eligible").booleanValue(),body.path("whatsappNumber").asText(null));
            return Map.of("configured",true);
        });
    }
    @PutMapping("/worksites/{id}") public ResponseEntity<?> worksite(HttpServletRequest http,@PathVariable long id,@RequestBody JsonNode body) {
        return guarded(http,() -> {
            fields(body,Set.of("selectable"));
            if (!body.path("selectable").isBoolean()) throw new ReportingProblem(400,"Provide selectable as a boolean.");
            service.configureSite(id,body.path("selectable").booleanValue()); return Map.of("configured",true);
        });
    }
    @PostMapping("/requests") public ResponseEntity<?> initiate(HttpServletRequest http,@RequestBody JsonNode body) {
        return guarded(http,() -> {
            fields(body,Set.of("workDate"));
            try { return service.initiate(LocalDate.parse(body.path("workDate").asText())); }
            catch (java.time.format.DateTimeParseException e) { throw new ReportingProblem(400,"workDate must be ISO yyyy-MM-dd."); }
        });
    }
    @GetMapping("/requests") public ResponseEntity<?> reports(HttpServletRequest http,@RequestParam(defaultValue="20") int limit) {
        return guarded(http,() -> service.reports(limit));
    }
    @GetMapping("/requests/{id}") public ResponseEntity<?> report(HttpServletRequest http,@PathVariable String id) {
        return guarded(http,() -> service.report(id));
    }
    @PostMapping("/outbound/{id}/dispatch") public ResponseEntity<?> dispatch(HttpServletRequest http,@PathVariable String id,@RequestBody(required=false) byte[] body) {
        return guarded(http,() -> {
            if (body!=null && body.length>0) throw new ReportingProblem(400,"Dispatch accepts no request body.");
            return service.dispatch(id);
        });
    }
    @PostMapping("/outbound/{id}/resolve") public ResponseEntity<?> resolve(HttpServletRequest http,@PathVariable String id,@RequestBody JsonNode body) {
        return guarded(http,() -> {
            fields(body,Set.of("decision","metaId"));
            return service.resolve(id,body.path("decision").asText(null),body.path("metaId").asText(null));
        });
    }
    @PostMapping("/requests/{id}/entries/{entryId}/resolve")
    public ResponseEntity<?> resolveEntry(HttpServletRequest http,@PathVariable String id,@PathVariable long entryId,@RequestBody JsonNode body) {
        return guarded(http,() -> {
            fields(body,Set.of("masterWorksiteId","create"));
            Long master=null;
            if(body.has("masterWorksiteId")) {
                if(!body.path("masterWorksiteId").isIntegralNumber() || !body.path("masterWorksiteId").canConvertToLong()) throw new ReportingProblem(400,"masterWorksiteId must be a positive integer.");
                master=body.path("masterWorksiteId").longValue();
            }
            life.work.IntFit.backend.dto.MasterWorksiteDTO create=null;
            if(body.has("create")) {
                fields(body.get("create"),Set.of("approvedName","nickname","notes","city","area","subArea","locationDetails","projectSizeTier","estimatedAreaM2"));
                try { create=mapper.treeToValue(body.get("create"),life.work.IntFit.backend.dto.MasterWorksiteDTO.class); }
                catch(Exception e) { throw new ReportingProblem(400,"Invalid new master definition."); }
            }
            return resolutions.resolve(id,entryId,master,create);
        });
    }
    private ResponseEntity<?> guarded(HttpServletRequest http,Supplier<?> operation) {
        // No query-string overrides or query-string credentials on these routes (list limit is the one exception).
        if (!settings.adminConfigured()) return error(503,"Reporting administration credential is not configured.");
        List<String> headers=Collections.list(http.getHeaders("X-WhatsApp-Reporting-Admin-Secret"));
        if (headers.size()!=1 || !settings.authorized(headers.get(0))) return error(403,"Reporting administration authorization rejected.");
        if (http.getQueryString()!=null && !(http.getMethod().equals("GET") && http.getRequestURI().endsWith("/requests")
                && http.getParameterMap().keySet().equals(Set.of("limit")) && http.getParameterValues("limit").length==1))
            return error(400,"Unexpected query parameters.");
        try { return ResponseEntity.ok().header("Cache-Control","no-store").body(operation.get()); }
        catch (ReportingProblem e) { return error(e.status,e.getMessage()); }
        catch (DuplicateKeyException e) { return error(409,"A reporting number or record already exists. Inspect existing configuration."); }
        catch (DataAccessException e) { return error(503,"Reporting storage unavailable; inspect schema and server health. No send retry was requested."); }
        catch (RuntimeException e) { return error(503,"Reporting operation could not be completed. Inspect persisted state before further action."); }
    }
    private static void fields(JsonNode body,Set<String> allowed) {
        if (body==null || !body.isObject()) throw new ReportingProblem(400,"A JSON object is required.");
        Iterator<String> names=body.fieldNames();
        while (names.hasNext()) if (!allowed.contains(names.next())) throw new ReportingProblem(400,"Unexpected request field.");
    }
    private static ResponseEntity<?> error(int status,String message) {
        return ResponseEntity.status(status).header("Cache-Control","no-store").body(Map.of("message",message));
    }
    @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> invalidInput() {
        // Suppress default resolver logging of malformed bodies/path values containing personal data.
        return error(400,"Invalid reporting administration input.");
    }
}
