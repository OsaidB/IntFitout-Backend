package life.work.IntFit.backend.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.servlet.http.HttpServletRequest;
import life.work.IntFit.backend.service.WhatsAppTestSendService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class WhatsAppTestSendController {

    private final WhatsAppTestSendService service;

    public WhatsAppTestSendController(WhatsAppTestSendService service) {
        this.service = service;
    }

    @PostMapping(value = "/api/whatsapp/test-send", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TestSendResponse> send(
            @RequestHeader HttpHeaders headers,
            @RequestBody(required = false) byte[] body,
            HttpServletRequest request) {
        List<String> secrets = headers.get("X-WhatsApp-Test-Send-Secret");
        String secret = secrets != null && secrets.size() == 1 ? secrets.get(0) : null;
        boolean hasOverrides = request.getQueryString() != null || (body != null && body.length != 0);
        WhatsAppTestSendService.SendResult result = service.send(secret, hasOverrides);
        return ResponseEntity.status(result.httpStatus()).cacheControl(CacheControl.noStore())
                .body(new TestSendResponse(result.status(), result.outgoingMessageId(), result.message()));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestSendResponse(String status, String outgoingMessageId, String message) { }
}
