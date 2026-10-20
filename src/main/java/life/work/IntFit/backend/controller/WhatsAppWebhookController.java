package life.work.IntFit.backend.controller;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

/** Connectivity prototype: authenticates and acknowledges events, without durable storage. */
@RestController
public class WhatsAppWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppWebhookController.class);
    private static final Pattern SIGNATURE = Pattern.compile("sha256=[a-fA-F0-9]{64}");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9]{5,15}");
    private static final Pattern LOG_PHONE = Pattern.compile(
            "(?<![A-Za-z0-9])\\+?[0-9][0-9 ().-]{6,}[0-9](?![A-Za-z0-9])");
    private static final Set<String> MESSAGE_TYPES = Set.of(
            "text", "image", "audio", "video", "document", "sticker", "location",
            "contacts", "interactive", "button", "reaction", "order", "system", "unknown");
    private static final Set<String> DELIVERY_STATUSES = Set.of("sent", "delivered", "read", "failed", "deleted");
    private static final MediaType TEXT_UTF8 = new MediaType(MediaType.TEXT_PLAIN, StandardCharsets.UTF_8);

    private final ObjectMapper objectMapper;
    private final ObjectReader jsonReader;
    private final String verifyToken;
    private final String appSecret;

    @Autowired
    public WhatsAppWebhookController(ObjectMapper objectMapper) {
        this(objectMapper, System.getenv("WHATSAPP_WEBHOOK_VERIFY_TOKEN"), System.getenv("WHATSAPP_APP_SECRET"));
    }

    // Explicit values allow isolated tests; production configuration comes only from the environment.
    WhatsAppWebhookController(ObjectMapper objectMapper, String verifyToken, String appSecret) {
        this.objectMapper = objectMapper;
        this.jsonReader = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        this.verifyToken = verifyToken;
        this.appSecret = appSecret;
    }

    @GetMapping(value = "/webhooks/whatsapp", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> verify(
            @RequestParam(name = "hub.mode", required = false) String mode,
            @RequestParam(name = "hub.verify_token", required = false) String token,
            @RequestParam(name = "hub.challenge", required = false) String challenge) {
        if (!configured()) return unavailable();
        if (!"subscribe".equals(mode) || token == null || challenge == null
                || !MessageDigest.isEqual(verifyToken.getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8))) {
            return text(HttpStatus.FORBIDDEN, "Webhook verification rejected.");
        }
        return text(HttpStatus.OK, challenge);
    }

    @PostMapping(value = "/webhooks/whatsapp", produces = "text/plain;charset=UTF-8")
    public ResponseEntity<String> receive(
            @RequestHeader(name = "X-Hub-Signature-256", required = false) String signature,
            @RequestBody(required = false) byte[] body) {
        if (!configured()) return unavailable();
        byte[] rawBody = body == null ? new byte[0] : body;
        try {
            if (!authentic(signature, rawBody)) {
                return text(HttpStatus.FORBIDDEN, "Webhook signature rejected.");
            }
        } catch (GeneralSecurityException e) {
            return unavailable();
        }

        // Never deserialize, inspect or log the body until its original bytes have been authenticated.
        final JsonNode event;
        try {
            event = jsonReader.readTree(rawBody);
        } catch (IOException e) {
            return text(HttpStatus.BAD_REQUEST, "Invalid webhook JSON.");
        }
        if (event == null || !event.isObject()) {
            return text(HttpStatus.BAD_REQUEST, "Invalid webhook JSON.");
        }
        if ("whatsapp_business_account".equals(event.path("object").asText())) {
            for (JsonNode entry : elements(event.path("entry"))) {
                for (JsonNode change : elements(entry.path("changes"))) {
                    if (!"messages".equals(change.path("field").asText())) continue;
                    JsonNode value = change.path("value");
                    for (JsonNode message : elements(value.path("messages"))) {
                        if (message.isObject()) logMessage(message);
                    }
                    for (JsonNode status : elements(value.path("statuses"))) {
                        if (status.isObject()) logStatus(status);
                    }
                }
            }
        }
        return text(HttpStatus.OK, "EVENT_RECEIVED");
    }

    private boolean configured() {
        return verifyToken != null && !verifyToken.isBlank() && appSecret != null && !appSecret.isBlank();
    }

    private boolean authentic(String signature, byte[] body) throws GeneralSecurityException {
        if (signature == null || !SIGNATURE.matcher(signature).matches()) return false;
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] expected = mac.doFinal(body);
        byte[] supplied = HexFormat.of().parseHex(signature.substring("sha256=".length()));
        return MessageDigest.isEqual(expected, supplied);
    }

    private void logMessage(JsonNode message) {
        JsonNode interactive = message.path("interactive");
        boolean buttonReply = "interactive".equals(message.path("type").asText())
                && "button_reply".equals(interactive.path("type").asText());
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("event", buttonReply ? "button_reply" : "message");
        summary.put("message_id", safeText(message.path("id")));
        summary.put("original_outgoing_message_id", safeText(message.path("context").path("id")));
        summary.put("sender", maskedPhone(message.path("from")));
        if (buttonReply) {
            JsonNode reply = interactive.path("button_reply");
            summary.put("button_id", safeText(reply.path("id")));
            summary.put("button_title", safeText(reply.path("title")));
        } else {
            summary.put("type", knownValue(message.path("type"), MESSAGE_TYPES));
        }
        log.info("whatsapp_webhook {}", summary);
    }

    private void logStatus(JsonNode status) {
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("event", "delivery_status");
        summary.put("message_id", safeText(status.path("id")));
        summary.put("status", knownValue(status.path("status"), DELIVERY_STATUSES));
        summary.put("recipient", maskedPhone(status.path("recipient_id")));
        log.info("whatsapp_webhook {}", summary);
    }

    private static Iterable<JsonNode> elements(JsonNode node) {
        return node.isArray() ? node : Collections.emptyList();
    }

    private static String knownValue(JsonNode node, Set<String> allowed) {
        return node.isTextual() && allowed.contains(node.textValue()) ? node.textValue() : "other";
    }

    private static String maskedPhone(JsonNode node) {
        if (!node.isTextual() || !PHONE.matcher(node.textValue()).matches()) return "[masked]";
        String phone = node.textValue();
        return "***" + phone.substring(phone.length() - 4);
    }

    private String safeText(JsonNode node) {
        if (!node.isTextual()) return null;
        // Redact configured secrets and phone-like values, bound each field, and remove log controls.
        // ObjectNode serialization additionally escapes JSON delimiters, keeping one structured log line.
        String value = node.textValue().replace(verifyToken, "[redacted]").replace(appSecret, "[redacted]");
        value = LOG_PHONE.matcher(value).replaceAll("[redacted-number]");
        StringBuilder safe = new StringBuilder();
        value.codePoints().limit(120).forEach(cp -> {
            int type = Character.getType(cp);
            safe.appendCodePoint(Character.isISOControl(cp) || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR ? ' ' : cp);
        });
        if (value.codePointCount(0, value.length()) > 120) safe.append("...");
        return safe.toString();
    }

    private static ResponseEntity<String> unavailable() {
        return text(HttpStatus.SERVICE_UNAVAILABLE, "WhatsApp webhook unavailable: required configuration or signature verification is unavailable.");
    }

    private static ResponseEntity<String> text(HttpStatus status, String body) {
        return ResponseEntity.status(status).contentType(TEXT_UTF8).body(body);
    }
}
