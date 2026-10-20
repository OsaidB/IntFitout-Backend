package life.work.IntFit.backend.service;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import jakarta.annotation.PreDestroy;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Manual connectivity test only. Never sends on construction, startup or a schedule. */
@Service
public class WhatsAppTestSendService {

    private static final Timeout CONNECTION_TIMEOUT = Timeout.ofSeconds(3);
    private static final Timeout RESPONSE_TIMEOUT = Timeout.ofSeconds(10);
    private static final int MAX_RESPONSE_BYTES = 64 * 1024;
    private static final Pattern MESSAGE_ID = Pattern.compile("wamid\\.[A-Za-z0-9._:+/=-]{1,512}");
    private static final RequestConfig REQUEST_CONFIG = RequestConfig.custom()
            .setConnectionRequestTimeout(CONNECTION_TIMEOUT)
            .setResponseTimeout(RESPONSE_TIMEOUT)
            .build();

    private final ObjectMapper objectMapper;
    private final ObjectReader jsonReader;
    private final CloseableHttpClient client;
    private final Settings settings;

    @Autowired
    public WhatsAppTestSendService(ObjectMapper objectMapper) {
        this(objectMapper, createClient(), System::getenv);
    }

    // Tests supply a mocked transport and synthetic environment; production reads only environment variables.
    WhatsAppTestSendService(ObjectMapper objectMapper, CloseableHttpClient client, Function<String, String> environment) {
        this.objectMapper = objectMapper;
        this.jsonReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.client = client;
        this.settings = new Settings(environment);
    }

    public SendResult send(String suppliedSecret, boolean hasRequestOverrides) {
        if (!settings.enabled) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "WhatsApp test sending is disabled.");
        }
        if (!settings.valid()) {
            return failure(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", "WhatsApp test sending has missing or invalid configuration.");
        }
        if (suppliedSecret == null || !MessageDigest.isEqual(settings.sendSecret.getBytes(StandardCharsets.UTF_8),
                suppliedSecret.getBytes(StandardCharsets.UTF_8))) {
            return failure(HttpStatus.FORBIDDEN, "unauthorized", "Unauthorized test-send request.");
        }
        if (hasRequestOverrides) {
            return failure(HttpStatus.BAD_REQUEST, "invalid_request", "This endpoint accepts no query parameters or request body.");
        }

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", settings.recipient,
                "type", "template",
                "template", Map.of("name", "hello_world", "language", Map.of("code", "en_US")));
        HttpPost request = new HttpPost("https://graph.facebook.com/" + settings.version + "/" + settings.phoneNumberId + "/messages");
        request.setConfig(REQUEST_CONFIG);
        request.setHeader(HttpHeaders.AUTHORIZATION, "Bearer " + settings.accessToken);
        request.setHeader(HttpHeaders.ACCEPT, "application/json");
        try {
            request.setEntity(new ByteArrayEntity(objectMapper.writeValueAsBytes(payload), ContentType.APPLICATION_JSON));
            return client.execute(request, this::providerResult);
        } catch (InterruptedIOException e) {
            // Acceptance can be ambiguous after a timeout; retrying might send a second message.
            return failure(HttpStatus.GATEWAY_TIMEOUT, "timeout", "Meta request timed out; acceptance is unknown. No automatic retry was attempted.");
        } catch (IOException e) {
            return failure(HttpStatus.BAD_GATEWAY, "transport_error", "Could not confirm Meta acceptance. No automatic retry was attempted.");
        }
    }

    private SendResult providerResult(ClassicHttpResponse response) throws IOException {
        if (response.getCode() < 200 || response.getCode() >= 300) {
            return failure(HttpStatus.BAD_GATEWAY, "provider_error", "Meta did not return a successful acceptance response.");
        }
        if (response.getEntity() == null) return invalidProviderResponse();
        byte[] bytes = response.getEntity().getContent().readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) return invalidProviderResponse();
        final JsonNode body;
        try {
            body = jsonReader.readTree(bytes);
        } catch (IOException e) {
            return invalidProviderResponse();
        }
        if (body == null || !body.isObject() || body.has("error")
                || !"whatsapp".equals(body.path("messaging_product").asText())) return invalidProviderResponse();
        JsonNode messages = body.path("messages");
        if (!messages.isArray() || messages.size() != 1 || !messages.get(0).path("id").isTextual()) {
            return invalidProviderResponse();
        }
        String id = messages.get(0).path("id").textValue();
        if (!MESSAGE_ID.matcher(id).matches() || id.contains(settings.accessToken)
                || id.contains(settings.sendSecret) || id.contains(settings.recipient)) return invalidProviderResponse();
        return new SendResult(HttpStatus.ACCEPTED, "accepted", id,
                "Meta accepted the request; delivery is confirmed separately through the webhook.");
    }

    private static SendResult invalidProviderResponse() {
        return failure(HttpStatus.BAD_GATEWAY, "provider_error", "Meta acceptance could not be confirmed from the response.");
    }

    private static SendResult failure(HttpStatus httpStatus, String status, String message) {
        return new SendResult(httpStatus, status, null, message);
    }

    private static CloseableHttpClient createClient() {
        return HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(CONNECTION_TIMEOUT)
                                .setSocketTimeout(RESPONSE_TIMEOUT)
                                .build())
                        .build())
                .disableAutomaticRetries()
                .disableRedirectHandling()
                .build();
    }

    @PreDestroy
    public void close() {
        try {
            client.close();
        } catch (IOException ignored) {
            // Do not log transport exceptions, which may contain request or provider details.
        }
    }

    public record SendResult(HttpStatus httpStatus, String status, String outgoingMessageId, String message) { }

    // Deliberately not a record: its generated toString must never expose configuration secrets.
    private static final class Settings {
        private final boolean enabled;
        private final String accessToken;
        private final String phoneNumberId;
        private final String version;
        private final String recipient;
        private final String sendSecret;

        private Settings(Function<String, String> environment) {
            enabled = "true".equalsIgnoreCase(environment.apply("WHATSAPP_TEST_SEND_ENABLED"));
            accessToken = environment.apply("WHATSAPP_ACCESS_TOKEN");
            phoneNumberId = environment.apply("WHATSAPP_PHONE_NUMBER_ID");
            String configuredVersion = environment.apply("WHATSAPP_GRAPH_API_VERSION");
            version = configuredVersion == null ? "v25.0" : configuredVersion;
            recipient = environment.apply("WHATSAPP_TEST_RECIPIENT");
            sendSecret = environment.apply("WHATSAPP_TEST_SEND_SECRET");
        }

        private boolean valid() {
            return headerSafe(accessToken) && headerSafe(sendSecret)
                    && phoneNumberId != null && phoneNumberId.matches("[0-9]{1,30}")
                    && version.matches("v[1-9][0-9]{0,2}\\.[0-9]{1,2}")
                    && recipient != null && recipient.matches("[1-9][0-9]{6,14}");
        }

        private static boolean headerSafe(String value) {
            return value != null && !value.isBlank() && value.codePoints().allMatch(cp -> cp >= 33 && cp <= 126);
        }
    }
}
