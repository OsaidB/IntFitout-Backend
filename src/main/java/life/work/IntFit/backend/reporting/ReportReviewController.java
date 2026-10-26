package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import life.work.IntFit.backend.dto.MasterWorksiteDTO;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/whatsapp/reporting/admin")
public class ReportReviewController {
    private final ReportReviewService reviews;
    private final ReportingResolutionService resolutions;
    private final ReportingAdminGuard guard;
    private final ObjectMapper strict;
    public ReportReviewController(ReportReviewService reviews,ReportingResolutionService resolutions,ReportingSettings settings,ObjectMapper mapper) {
        this.reviews=reviews;this.resolutions=resolutions;this.guard=new ReportingAdminGuard(settings);
        this.strict=mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        strict.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        strict.coercionConfigFor(java.math.BigDecimal.class).setCoercion(com.fasterxml.jackson.databind.cfg.CoercionInputShape.String,com.fasterxml.jackson.databind.cfg.CoercionAction.TryConvert);
    }
    @GetMapping("/reviews") public ResponseEntity<?> list(HttpServletRequest h) {
        return guard.execute(h,Set.of("date","status","page","size"),()-> {
            LocalDate date=null;try{if(h.getParameter("date")!=null)date=LocalDate.parse(h.getParameter("date"));}
            catch(Exception e){throw new ReportingProblem(400,"Date must be yyyy-MM-dd.");}
            return reviews.list(date,param(h,"status","AWAITING_REVIEW"),integer(h,"page",0),integer(h,"size",30));
        });
    }
    @GetMapping("/reviews/{id}") public ResponseEntity<?> detail(HttpServletRequest h,@PathVariable String id) {
        return guard.execute(h,Set.of(),()->reviews.detail(id));
    }
    @GetMapping("/review-masters") public ResponseEntity<?> masters(HttpServletRequest h) {
        return guard.execute(h,Set.of("q","page"),()->reviews.masters(param(h,"q",""),integer(h,"page",0)));
    }
    @PostMapping("/reviews/{id}/approve") public ResponseEntity<?> approve(HttpServletRequest h,@PathVariable String id,@RequestBody byte[] body) {
        return guard.execute(h,Set.of(),()-> {
            JsonNode n=parse(body);fields(n,Set.of("expectedRevision","outcome","masterWorksiteIds","includedEntryIds","overtimeHours","regular","overtime","removeExistingConfirmed"));
            if(!n.path("expectedRevision").isIntegralNumber()||!n.path("expectedRevision").canConvertToLong()||!n.path("outcome").isTextual()||!n.path("masterWorksiteIds").isArray()||!n.path("includedEntryIds").isArray()||!n.path("removeExistingConfirmed").isBoolean())invalid();
            for(String key:List.of("masterWorksiteIds","includedEntryIds"))for(JsonNode item:n.path(key))if(!item.isIntegralNumber()||!item.canConvertToLong())invalid();
            if(n.has("overtimeHours") && !n.get("overtimeHours").isNull() && !n.get("overtimeHours").isTextual() && !n.get("overtimeHours").isNumber())invalid();
            for(String category:List.of("regular","overtime"))if(n.hasNonNull(category)) {
                JsonNode c=n.get(category);fields(c,Set.of("mode","hours"));if(!c.path("mode").isTextual())invalid();
                if(c.hasNonNull("hours")){if(!c.get("hours").isArray()||c.get("hours").size()>100)invalid();for(JsonNode item:c.get("hours")){fields(item,Set.of("masterWorksiteId","hours"));if(!item.path("masterWorksiteId").isIntegralNumber()||!item.path("masterWorksiteId").canConvertToLong()||(!item.path("hours").isNumber()&&!item.path("hours").isTextual()))invalid();}}
            }
            try{return reviews.approve(id,strict.treeToValue(n,ReportReviewService.Decision.class));}
            catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new ReportingProblem(400,"Invalid approval decision.");}
        });
    }
    @PostMapping("/reviews/{id}/entries/{entry}/resolve") public ResponseEntity<?> resolve(HttpServletRequest h,@PathVariable String id,@PathVariable long entry,@RequestBody byte[] body) {
        return guard.execute(h,Set.of(),()-> {
            JsonNode n=parse(body);fields(n,Set.of("masterWorksiteId","create"));Long master=null;MasterWorksiteDTO create=null;
            if(n.has("masterWorksiteId")){if(!n.get("masterWorksiteId").isIntegralNumber()||!n.get("masterWorksiteId").canConvertToLong())invalid();master=n.get("masterWorksiteId").longValue();}
            if(n.has("create")) {
                fields(n.get("create"),Set.of("approvedName","nickname","notes","city","area","subArea","locationDetails","projectSizeTier","estimatedAreaM2"));
                try{create=strict.treeToValue(n.get("create"),MasterWorksiteDTO.class);}catch(Exception e){throw new ReportingProblem(400,"Invalid master definition.");}
            }
            return resolutions.resolve(id,entry,master,create);
        });
    }
    private JsonNode parse(byte[] body){try{if(body.length>65536)invalid();return strict.readTree(body);}catch(Exception e){throw new ReportingProblem(400,"Invalid review JSON.");}}
    private static void fields(JsonNode n,Set<String> allowed){if(n==null||!n.isObject())invalid();n.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))invalid();});}
    private static void invalid(){throw new ReportingProblem(400,"Invalid review input.");}
    private static String param(HttpServletRequest h,String k,String d){return h.getParameter(k)==null?d:h.getParameter(k);}
    private static int integer(HttpServletRequest h,String k,int d){try{String s=h.getParameter(k);if(s==null)return d;if(!s.matches("0|[1-9][0-9]{0,5}"))invalid();return Integer.parseInt(s);}catch(NumberFormatException e){throw new ReportingProblem(400,"Invalid pagination.");}}
    @ExceptionHandler({org.springframework.http.converter.HttpMessageNotReadableException.class,org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class})
    public ResponseEntity<?> badInput(){return ReportingAdminGuard.error(400,"Invalid review input.");}
}
