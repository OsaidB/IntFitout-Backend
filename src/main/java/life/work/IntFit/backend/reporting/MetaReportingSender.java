package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import life.work.IntFit.backend.config.WhatsAppHttpTransport;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
public class MetaReportingSender implements ReportingSender {
    private final ReportingSettings settings;
    private final ObjectMapper mapper;
    private final CloseableHttpClient http;
    @Autowired public MetaReportingSender(ReportingSettings settings, ObjectMapper mapper) {
        this(settings,mapper,WhatsAppHttpTransport.create());
    }
    MetaReportingSender(ReportingSettings settings,ObjectMapper mapper,CloseableHttpClient http) {
        this.settings=settings; this.mapper=mapper; this.http=http;
    }
    @Override public Result send(String generatedPayload) {
        if (!settings.sendingReady()) return new Result("REJECTED",null);
        try {
            JsonNode payload = mapper.readTree(generatedPayload);
            // Defense in depth against a stale/corrupted outbox changing the pilot recipient.
            if (!settings.recipient.equals(payload.path("to").asText())
                    || !"whatsapp".equals(payload.path("messaging_product").asText())) return new Result("REJECTED",null);
            HttpPost request = new HttpPost("https://graph.facebook.com/"+settings.version+"/"+settings.phoneId+"/messages");
            request.setConfig(WhatsAppHttpTransport.requestConfig());
            request.setHeader(HttpHeaders.AUTHORIZATION,"Bearer "+settings.accessToken);
            request.setHeader(HttpHeaders.ACCEPT,"application/json");
            request.setEntity(new ByteArrayEntity(generatedPayload.getBytes(StandardCharsets.UTF_8),ContentType.APPLICATION_JSON));
            return http.execute(request,response -> {
                if (response.getCode()<200 || response.getCode()>=300) return new Result("REJECTED",null);
                if (response.getEntity()==null) return new Result("UNCERTAIN",null);
                byte[] bytes=response.getEntity().getContent().readNBytes(65537);
                if (bytes.length>65536) return new Result("UNCERTAIN",null);
                JsonNode body=mapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
                JsonNode messages=body == null ? mapper.createArrayNode() : body.path("messages");
                if (body == null || body.has("error") || !"whatsapp".equals(body.path("messaging_product").asText())
                        || !messages.isArray() || messages.size()!=1) return new Result("UNCERTAIN",null);
                String id=messages.get(0).path("id").asText("");
                return safeId(id,settings) ? new Result("ACCEPTED",id) : new Result("UNCERTAIN",null);
            });
        } catch (IOException | RuntimeException ignored) {
            // Never log exception messages, headers, payloads, recipient, or provider response bodies.
            return new Result("UNCERTAIN",null);
        }
    }
    static boolean safeId(String id,ReportingSettings s) {
        return id != null && id.matches("wamid\\.[A-Za-z0-9._:+/=-]{1,512}")
                && (s.recipient == null || !id.contains(s.recipient)) && (s.accessToken == null || !id.contains(s.accessToken))
                && (s.adminSecret == null || !id.contains(s.adminSecret));
    }
    @PreDestroy public void close() { try { http.close(); } catch (IOException ignored) { } }
}
