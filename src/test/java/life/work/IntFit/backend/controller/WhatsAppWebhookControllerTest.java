package life.work.IntFit.backend.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Standalone servlet tests only: no Boot context, application configuration, repositories or database. */
class WhatsAppWebhookControllerTest {

    private static final String ENDPOINT = "/webhooks/whatsapp";
    private static final String SIGNATURE_HEADER = "X-Hub-Signature-256";
    private static final String VERIFY_TOKEN = "test-verify-token-only";
    private static final String APP_SECRET = "test-app-secret-only";
    private static final String SENDER = "15555550123";
    private static final String MESSAGE_ID = "wamid.incoming-example";
    private static final String OUTGOING_ID = "wamid.outgoing-example";

    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger logger = (Logger) LoggerFactory.getLogger(WhatsAppWebhookController.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private MockMvc mvc;
    private Level previousLevel;
    private boolean previousAdditive;

    @BeforeEach
    void setUp() {
        previousLevel = logger.getLevel();
        previousAdditive = logger.isAdditive();
        logger.setLevel(Level.INFO);
        logger.setAdditive(false);
        appender.start();
        logger.addAppender(appender);
        mvc = standalone(VERIFY_TOKEN, APP_SECRET);
    }

    @AfterEach
    void restoreLogger() {
        logger.detachAppender(appender);
        appender.stop();
        logger.setLevel(previousLevel);
        logger.setAdditive(previousAdditive);
    }

    private MockMvc standalone(String token, String secret) {
        return MockMvcBuilders.standaloneSetup(new WhatsAppWebhookController(mapper, token, secret)).build();
    }

    @Test
    void verifiesGetAndPreservesChallengeExactly() throws Exception {
        String challenge = " 00123+challenge\nunchanged ";
        mvc.perform(get(ENDPOINT).param("hub.mode", "subscribe")
                        .param("hub.verify_token", VERIFY_TOKEN).param("hub.challenge", challenge))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/plain;charset=UTF-8"))
                .andExpect(content().string(challenge));
        assertThat(appender.list).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"wrong-token", " test-verify-token-only "})
    void rejectsMissingOrIncorrectVerifyToken(String token) throws Exception {
        var request = get(ENDPOINT).param("hub.mode", "subscribe").param("hub.challenge", "123");
        if (token != null) request.param("hub.verify_token", token);
        mvc.perform(request).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unsubscribe", "SUBSCRIBE"})
    void rejectsMissingOrIncorrectMode(String mode) throws Exception {
        var request = get(ENDPOINT).param("hub.verify_token", VERIFY_TOKEN).param("hub.challenge", "123");
        if (mode != null) request.param("hub.mode", mode);
        mvc.perform(request).andExpect(status().isForbidden());
    }

    @Test
    void rejectsMissingChallenge() throws Exception {
        mvc.perform(get(ENDPOINT).param("hub.mode", "subscribe").param("hub.verify_token", VERIFY_TOKEN))
                .andExpect(status().isForbidden());
    }

    static Stream<Arguments> missingConfiguration() {
        return Stream.of(
                Arguments.of(null, APP_SECRET), Arguments.of("", APP_SECRET), Arguments.of(" \t\n", APP_SECRET),
                Arguments.of(VERIFY_TOKEN, null), Arguments.of(VERIFY_TOKEN, ""), Arguments.of(VERIFY_TOKEN, " \t\n"),
                Arguments.of(null, null));
    }

    @ParameterizedTest
    @MethodSource("missingConfiguration")
    void missingConfigurationLeavesBothEndpointMethodsUnavailable(String token, String secret) throws Exception {
        MockMvc unavailable = standalone(token, secret);
        unavailable.perform(get(ENDPOINT).param("hub.mode", "subscribe")
                        .param("hub.verify_token", VERIFY_TOKEN).param("hub.challenge", "123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("WhatsApp webhook unavailable")));
        unavailable.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content("not JSON"))
                .andExpect(status().isServiceUnavailable());
        assertThat(appender.list).isEmpty();
    }

    @Test
    void authenticatesOriginalBytesAndLogsButtonReplyWithoutPrivateFields() throws Exception {
        ObjectNode event = buttonEvent("Site A");
        JsonNode value = event.path("entry").get(0).path("changes").get(0).path("value");
        ((ObjectNode) value).putArray("contacts").addObject().putObject("profile").put("name", "Ignored profile name");
        byte[] body = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(event);
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .header(SIGNATURE_HEADER, signature(body, APP_SECRET)).content(body))
                .andExpect(status().isOk()).andExpect(content().string("EVENT_RECEIVED"));

        assertThat(appender.list).hasSize(1);
        JsonNode summary = summaries().get(0);
        assertThat(summary.path("event").asText()).isEqualTo("button_reply");
        assertThat(summary.path("message_id").asText()).isEqualTo(MESSAGE_ID);
        assertThat(summary.path("original_outgoing_message_id").asText()).isEqualTo(OUTGOING_ID);
        assertThat(summary.path("sender").asText()).isEqualTo("***0123");
        assertThat(summary.path("button_id").asText()).isEqualTo("test_site_a");
        assertThat(summary.path("button_title").asText()).isEqualTo("Site A");
        assertThat(logged()).doesNotContain(SENDER, "Ignored profile name", VERIFY_TOKEN, APP_SECRET);
    }

    @Test
    void acceptsUppercaseSignatureHex() throws Exception {
        byte[] body = bytes(buttonEvent("Site A"));
        String header = signature(body, APP_SECRET);
        mvc.perform(post(ENDPOINT).header(SIGNATURE_HEADER,
                        "sha256=" + header.substring(7).toUpperCase(Locale.ROOT)).content(body))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsMissingSignatureBeforeParsingJson() throws Exception {
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content("not JSON"))
                .andExpect(status().isForbidden());
        assertThat(appender.list).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "sha1=abcd", "sha256=abcd", "sha256=", "SHA256=abcd",
            "sha256=gggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggggg"})
    void rejectsMalformedSignatures(String header) throws Exception {
        mvc.perform(post(ENDPOINT).header(SIGNATURE_HEADER, header).content(bytes(buttonEvent("Site A"))))
                .andExpect(status().isForbidden());
        assertThat(appender.list).isEmpty();
    }

    @Test
    void rejectsWrongSecretAndTamperedBodyIncludingWhitespaceChanges() throws Exception {
        byte[] body = bytes(buttonEvent("Site A"));
        mvc.perform(post(ENDPOINT).header(SIGNATURE_HEADER, signature(body, "different-test-secret")).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(ENDPOINT).header(SIGNATURE_HEADER, signature(body, APP_SECRET))
                        .content(bytes(buttonEvent("Site B"))))
                .andExpect(status().isForbidden());
        mvc.perform(post(ENDPOINT).header(SIGNATURE_HEADER, signature(body, APP_SECRET))
                        .content(new String(body, StandardCharsets.UTF_8) + " "))
                .andExpect(status().isForbidden());
        assertThat(appender.list).isEmpty();
    }

    @Test
    void rejectsExtraSignatureHeadersAndWrongLengthDigests() throws Exception {
        byte[] body = bytes(buttonEvent("Site A"));
        String header = signature(body, APP_SECRET);
        for (String invalid : List.of(header + "," + header, header + "0", header.substring(0, header.length() - 1),
                " " + header, header + "\n")) {
            mvc.perform(post(ENDPOINT).header(SIGNATURE_HEADER, invalid).content(body))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post(ENDPOINT).header(SIGNATURE_HEADER, header, header).content(body))
                .andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(strings = {"not JSON", "{} {}", "null", "[]", ""})
    void rejectsAuthenticatedInvalidJsonWithoutLoggingPayload(String raw) throws Exception {
        byte[] body = raw.getBytes(StandardCharsets.UTF_8);
        mvc.perform(post(ENDPOINT).header(SIGNATURE_HEADER, signature(body, APP_SECRET)).content(body))
                .andExpect(status().isBadRequest()).andExpect(content().string("Invalid webhook JSON."));
        assertThat(appender.list).isEmpty();
    }

    @Test
    void handlesAllEntriesChangesMessagesAndDeliveryStatuses() throws Exception {
        ObjectNode event = buttonEvent("Site A");
        var entries = (com.fasterxml.jackson.databind.node.ArrayNode) event.path("entry");
        var changes = (com.fasterxml.jackson.databind.node.ArrayNode) entries.get(0).path("changes");
        var value = (ObjectNode) changes.get(0).path("value");
        var messages = (com.fasterxml.jackson.databind.node.ArrayNode) value.path("messages");
        messages.addObject().put("type", "text").put("id", "wamid.text-example").put("from", SENDER)
                .putObject("text").put("body", "must-not-log-raw-text");
        messages.addNull();
        var statuses = value.putArray("statuses");
        for (String status : List.of("sent", "delivered", "read", "failed")) {
            statuses.addObject().put("id", OUTGOING_ID).put("status", status).put("recipient_id", SENDER)
                    .putArray("errors").addObject().put("message", "must-not-log-error-detail");
        }
        changes.addObject().put("field", "messages").putObject("value").putArray("messages")
                .add(buttonMessage("Site B"));
        changes.addObject().put("field", "unrelated").set("value", value.deepCopy());
        entries.addObject().putArray("changes").addObject().put("field", "messages")
                .putObject("value").putArray("messages").add(buttonMessage("Site C"));
        postSigned(event);

        List<JsonNode> summaries = summaries();
        assertThat(summaries).hasSize(8);
        assertThat(summaries.stream().filter(n -> n.path("event").asText().equals("button_reply"))
                .map(n -> n.path("button_title").asText())).containsExactly("Site A", "Site B", "Site C");
        assertThat(summaries.stream().filter(n -> n.path("event").asText().equals("delivery_status"))
                .map(n -> n.path("status").asText())).containsExactly("sent", "delivered", "read", "failed");
        assertThat(logged()).doesNotContain(SENDER, "must-not-log-raw-text", "must-not-log-error-detail");
    }

    @Test
    void ignoresUnrelatedObjectsFieldsAndMalformedEventContainers() throws Exception {
        postSigned(mapper.createObjectNode().put("object", "page").set("entry", buttonEvent("Site A").path("entry")));
        postSigned(mapper.readTree("{\"object\":\"whatsapp_business_account\",\"entry\":{\"changes\":[]}}"));
        postSigned(mapper.readTree("{\"object\":\"whatsapp_business_account\",\"entry\":[null,{\"changes\":[null,{\"field\":\"messages\",\"value\":null}]}]}"));
        assertThat(appender.list).isEmpty();
    }

    @Test
    void boundsAndEscapesExternalLogFieldsAndRedactsPhonesAndConfiguredSecrets() throws Exception {
        String title = "Site \"A\"\n\r\t\u001b\u202e\u2028 +15555550123 " + APP_SECRET + " " + VERIFY_TOKEN + " " + "x".repeat(300);
        ObjectNode event = buttonEvent(title);
        ObjectNode message = (ObjectNode) event.path("entry").get(0).path("changes").get(0).path("value").path("messages").get(0);
        message.put("id", "wamid.\n\u202eexample");
        postSigned(event);
        String logged = logged();
        assertThat(logged).doesNotContain("\n", "\r", "\t", "\u001b", "\u202e", "\u2028", SENDER, APP_SECRET, VERIFY_TOKEN);
        JsonNode summary = summaries().get(0);
        assertThat(summary.path("button_title").asText()).contains("[redacted-number]", "[redacted]").endsWith("...");
        assertThat(summary.path("button_title").asText().codePointCount(0, summary.path("button_title").asText().length()))
                .isLessThanOrEqualTo(123);
    }

    @Test
    void handlesMissingContextNonTextFieldsAndShortOrInvalidSendersWithoutLeakingThem() throws Exception {
        ObjectNode event = buttonEvent("Site A");
        ObjectNode message = (ObjectNode) event.path("entry").get(0).path("changes").get(0).path("value").path("messages").get(0);
        message.remove("context");
        message.put("from", "1234");
        ((ObjectNode) message.path("interactive").path("button_reply")).putObject("title").put("name", "do-not-log-name");
        postSigned(event);
        assertThat(summaries().get(0).path("original_outgoing_message_id").isNull()).isTrue();
        assertThat(summaries().get(0).path("button_title").isNull()).isTrue();
        assertThat(summaries().get(0).path("sender").asText()).isEqualTo("[masked]");
        assertThat(logged()).doesNotContain("1234", "do-not-log-name");
    }

    private ObjectNode buttonMessage(String title) {
        ObjectNode message = mapper.createObjectNode().put("id", MESSAGE_ID).put("from", SENDER).put("type", "interactive");
        message.putObject("context").put("id", OUTGOING_ID);
        message.putObject("interactive").put("type", "button_reply")
                .putObject("button_reply").put("id", "test_site_a").put("title", title);
        return message;
    }

    private ObjectNode buttonEvent(String title) {
        ObjectNode event = mapper.createObjectNode().put("object", "whatsapp_business_account");
        event.putArray("entry").addObject().putArray("changes").addObject().put("field", "messages")
                .putObject("value").putArray("messages").add(buttonMessage(title));
        return event;
    }

    private byte[] bytes(JsonNode event) throws Exception {
        return mapper.writeValueAsBytes(event);
    }

    private void postSigned(JsonNode event) throws Exception {
        byte[] body = bytes(event);
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .header(SIGNATURE_HEADER, signature(body, APP_SECRET)).content(body))
                .andExpect(status().isOk());
    }

    private static String signature(byte[] body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }

    private String logged() {
        return String.join("", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
    }

    private List<JsonNode> summaries() throws Exception {
        var summaries = new java.util.ArrayList<JsonNode>();
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).startsWith("whatsapp_webhook ");
            summaries.add(mapper.readTree(event.getFormattedMessage().substring("whatsapp_webhook ".length())));
        }
        return summaries;
    }
}
