package life.work.IntFit.backend.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import life.work.IntFit.backend.reporting.ReportingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HexFormat;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class WhatsAppReportingWebhookTest {
    static final String SECRET="synthetic-webhook-signing-secret";
    final ObjectMapper mapper=new ObjectMapper();
    RecordingReporting reporting;
    MockMvc mvc;
    @BeforeEach void setup(){reporting=new RecordingReporting();mvc=MockMvcBuilders.standaloneSetup(new WhatsAppWebhookController(mapper,"verify",SECRET,reporting)).build();}
    String signature(String body) throws Exception {Mac m=Mac.getInstance("HmacSHA256");m.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return "sha256="+HexFormat.of().formatHex(m.doFinal(body.getBytes(StandardCharsets.UTF_8)));}
    String event="{\"object\":\"whatsapp_business_account\",\"entry\":[{\"changes\":[{\"field\":\"messages\",\"value\":{\"metadata\":{\"phone_number_id\":\"123400007\"}}},{\"field\":\"unrelated\",\"value\":{}},{\"field\":\"messages\",\"value\":{}}]},{\"changes\":[{\"field\":\"messages\",\"value\":{}}]}]}";
    @Test void originalByteSignatureIsCheckedBeforeJsonAndPersistence() throws Exception {
        mvc.perform(post("/webhooks/whatsapp").contentType("application/json").content(event)).andExpect(status().isForbidden());
        mvc.perform(post("/webhooks/whatsapp").header("X-Hub-Signature-256",signature(event)).contentType("application/json").content(event+" ")).andExpect(status().isForbidden());
        mvc.perform(post("/webhooks/whatsapp").header("X-Hub-Signature-256",signature("invalid")).contentType("application/json").content("invalid")).andExpect(status().isBadRequest());
        assertEquals(0,reporting.calls);
    }
    @Test void everyBatchedValueCompletesBeforeAcknowledgement() throws Exception {
        mvc.perform(post("/webhooks/whatsapp").header("X-Hub-Signature-256",signature(event)).contentType("application/json").content(event))
                .andExpect(status().isOk()).andExpect(content().string("EVENT_RECEIVED"));assertEquals(3,reporting.completed);
    }
    @Test void persistenceFailureWithholdsAcknowledgementAndDoesNotExposeException() throws Exception {
        reporting.fail=true;
        mvc.perform(post("/webhooks/whatsapp").header("X-Hub-Signature-256",signature(event)).contentType("application/json").content(event))
                .andExpect(status().isServiceUnavailable()).andExpect(content().string("Reporting processing unavailable; event acknowledgement withheld."));
        assertEquals(0,reporting.completed);
    }
    @Test void unrelatedObjectsRemainHarmless() throws Exception {
        String body="{\"object\":\"unrelated\",\"entry\":[]}";
        mvc.perform(post("/webhooks/whatsapp").header("X-Hub-Signature-256",signature(body)).contentType("application/json").content(body)).andExpect(status().isOk());assertEquals(0,reporting.calls);
    }
    static final class RecordingReporting extends ReportingService {
        int calls,completed;boolean fail;
        RecordingReporting(){super(null,null,null,Clock.systemUTC(),new ObjectMapper());}
        @Override public void acceptVerifiedValue(JsonNode value){calls++;if(fail)throw new IllegalStateException("synthetic-secret-must-not-appear");completed++;}
    }
}
