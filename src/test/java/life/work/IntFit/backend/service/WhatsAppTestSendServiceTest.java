package life.work.IntFit.backend.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import life.work.IntFit.backend.controller.WhatsAppTestSendController;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpHeaders;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Mocked Apache transport and standalone MVC only. No application context, database or network calls. */
class WhatsAppTestSendServiceTest {

    private static final String ENDPOINT = "/api/whatsapp/test-send";
    private static final String SECRET_HEADER = "X-WhatsApp-Test-Send-Secret";
    private static final String SECRET = "synthetic-test-send-secret";
    private static final String ACCESS_TOKEN = "synthetic-meta-access-token";
    private static final String PHONE_ID = "123456789012345";
    private static final String RECIPIENT = "15555550123";
    private static final String MESSAGE_ID = "wamid.test-outgoing-example";
    private static final String ACCEPTED_RESPONSE = """
            {"messaging_product":"whatsapp","contacts":[{"input":"15555550123","wa_id":"15555550123"}],
             "messages":[{"id":"wamid.test-outgoing-example"}],"private_field":"must-not-echo-provider-body"}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private StubHttpClient client;
    private Map<String, String> environment;
    private WhatsAppTestSendService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        client = new StubHttpClient();
        environment = new HashMap<>(Map.of(
                "WHATSAPP_ACCESS_TOKEN", ACCESS_TOKEN,
                "WHATSAPP_PHONE_NUMBER_ID", PHONE_ID,
                "WHATSAPP_GRAPH_API_VERSION", "v25.0",
                "WHATSAPP_TEST_RECIPIENT", RECIPIENT,
                "WHATSAPP_TEST_SEND_ENABLED", "true",
                "WHATSAPP_TEST_SEND_SECRET", SECRET));
        appender.start();
        rootLogger.addAppender(appender);
        rebuild();
    }

    @AfterEach
    void noSensitiveLogs() {
        rootLogger.detachAppender(appender);
        appender.stop();
        String logs = String.join("", appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList());
        assertThat(logs).doesNotContain(ACCESS_TOKEN, SECRET, RECIPIENT, "must-not-echo-provider-body");
    }

    private void rebuild() {
        service = new WhatsAppTestSendService(mapper, client, environment::get);
        mvc = MockMvcBuilders.standaloneSetup(new WhatsAppTestSendController(service)).build();
    }

    private MockHttpServletRequestBuilder authorizedPost() {
        return post(ENDPOINT).header(SECRET_HEADER, SECRET);
    }

    @Test
    void constructionNeverSendsAnything() {
        assertThat(client.requests).isEmpty();
    }

    @Test
    void internalScheduledInvocationUsesSharedSenderAndManualSecretProtectionRemains() throws Exception {
        assertThat(service.readyForScheduledTest()).isTrue();
        respond(200, ACCEPTED_RESPONSE);
        assertThat(service.sendScheduledTest().outgoingMessageId()).isEqualTo(MESSAGE_ID);
        mvc.perform(post(ENDPOINT)).andExpect(status().isForbidden());
        assertThat(capturedRequest().getUri().toString())
                .isEqualTo("https://graph.facebook.com/v25.0/" + PHONE_ID + "/messages");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"false", "0", "yes", " true "})
    void disabledByDefaultAndOnlyExplicitTrueEnablesSending(String enabled) throws Exception {
        environment.put("WHATSAPP_TEST_SEND_ENABLED", enabled);
        rebuild();
        mvc.perform(authorizedPost()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("unavailable"));
        assertThat(service.readyForScheduledTest()).isFalse();
        assertThat(service.sendScheduledTest().status()).isEqualTo("unavailable");
        assertThat(client.requests).isEmpty();
    }

    static Stream<Arguments> invalidConfiguration() {
        Stream<Arguments> missing = Stream.of("WHATSAPP_ACCESS_TOKEN", "WHATSAPP_PHONE_NUMBER_ID",
                        "WHATSAPP_TEST_RECIPIENT", "WHATSAPP_TEST_SEND_SECRET")
                .flatMap(key -> Stream.of(Arguments.of(key, null), Arguments.of(key, ""), Arguments.of(key, " \t")));
        return Stream.concat(missing, Stream.of(
                Arguments.of("WHATSAPP_ACCESS_TOKEN", "token\r\nInjected: header"),
                Arguments.of("WHATSAPP_TEST_SEND_SECRET", "secret\n"),
                Arguments.of("WHATSAPP_PHONE_NUMBER_ID", "../messages"),
                Arguments.of("WHATSAPP_PHONE_NUMBER_ID", "123?to=456"),
                Arguments.of("WHATSAPP_TEST_RECIPIENT", "+15555550123"),
                Arguments.of("WHATSAPP_TEST_RECIPIENT", "1555 555 0123"),
                Arguments.of("WHATSAPP_TEST_RECIPIENT", "0"),
                Arguments.of("WHATSAPP_GRAPH_API_VERSION", ""),
                Arguments.of("WHATSAPP_GRAPH_API_VERSION", " "),
                Arguments.of("WHATSAPP_GRAPH_API_VERSION", "https://example.invalid"),
                Arguments.of("WHATSAPP_GRAPH_API_VERSION", "v25.0/../../other")));
    }

    @ParameterizedTest
    @MethodSource("invalidConfiguration")
    void invalidConfigurationFailsClosedAtRequestTime(String key, String value) throws Exception {
        environment.put(key, value);
        rebuild();
        mvc.perform(authorizedPost()).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.outgoingMessageId").doesNotExist());
        assertThat(service.readyForScheduledTest()).isFalse();
        assertThat(service.sendScheduledTest().status()).isEqualTo("unavailable");
        assertThat(client.requests).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"incorrect-secret", " synthetic-test-send-secret "})
    void missingOrIncorrectDedicatedSecretIsRejected(String secret) throws Exception {
        var request = post(ENDPOINT).header(HttpHeaders.AUTHORIZATION, "Bearer unrelated-app-authentication");
        if (secret != null) request.header(SECRET_HEADER, secret);
        mvc.perform(request).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value("unauthorized"));
        assertThat(client.requests).isEmpty();
    }

    @Test
    void rejectsDuplicateSecretHeaders() throws Exception {
        mvc.perform(post(ENDPOINT).header(SECRET_HEADER, SECRET, SECRET)).andExpect(status().isForbidden());
        assertThat(client.requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", " ", "{\"to\":\"15555550999\",\"template\":\"other\"}", "not JSON"})
    void rejectsAllRequestBodiesWithoutParsingThem(String body) throws Exception {
        mvc.perform(authorizedPost().contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.status").value("invalid_request"));
        assertThat(client.requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"to", "recipient", "message", "template", "url"})
    void rejectsQueryParametersRatherThanAcceptingOverrides(String key) throws Exception {
        mvc.perform(authorizedPost().queryParam(key, "override"))
                .andExpect(status().isBadRequest());
        assertThat(client.requests).isEmpty();
    }

    @Test
    void endpointIsPostOnly() throws Exception {
        for (var method : List.of(get(ENDPOINT), put(ENDPOINT), patch(ENDPOINT), delete(ENDPOINT))) {
            mvc.perform(method.header(SECRET_HEADER, SECRET)).andExpect(status().isMethodNotAllowed());
        }
        assertThat(client.requests).isEmpty();
    }

    @Test
    void sendsOnlyConfiguredRecipientAndFixedHelloWorldTemplateAndReturnsOnlyAcceptanceSummary() throws Exception {
        respond(200, ACCEPTED_RESPONSE);
        mvc.perform(authorizedPost())
                .andExpect(status().isAccepted())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.outgoingMessageId").value(MESSAGE_ID))
                .andExpect(jsonPath("$.message").value("Meta accepted the request; delivery is confirmed separately through the webhook."))
                .andExpect(jsonPath("$.contacts").doesNotExist())
                .andExpect(content().string(not(containsString(RECIPIENT))))
                .andExpect(content().string(not(containsString("must-not-echo-provider-body"))));

        HttpPost outgoing = capturedRequest();
        assertThat(outgoing.getMethod()).isEqualTo("POST");
        assertThat(outgoing.getUri().toString()).isEqualTo("https://graph.facebook.com/v25.0/" + PHONE_ID + "/messages");
        assertThat(outgoing.getFirstHeader(HttpHeaders.AUTHORIZATION).getValue()).isEqualTo("Bearer " + ACCESS_TOKEN);
        assertThat(outgoing.getEntity().getContentType()).startsWith("application/json");
        assertThat(outgoing.getConfig().getConnectionRequestTimeout().toMilliseconds()).isEqualTo(3000);
        assertThat(outgoing.getConfig().getResponseTimeout().toMilliseconds()).isEqualTo(10000);
        JsonNode sent = mapper.readTree(EntityUtils.toString(outgoing.getEntity()));
        assertThat(sent).isEqualTo(mapper.readTree("""
                {"messaging_product":"whatsapp","to":"15555550123","type":"template",
                 "template":{"name":"hello_world","language":{"code":"en_US"}}}
                """));
    }

    @Test
    void graphVersionDefaultsToV25WhenEnvironmentVariableIsAbsent() throws Exception {
        environment.remove("WHATSAPP_GRAPH_API_VERSION");
        rebuild();
        respond(200, ACCEPTED_RESPONSE);
        mvc.perform(authorizedPost()).andExpect(status().isAccepted());
        assertThat(capturedRequest().getUri().getPath()).isEqualTo("/v25.0/" + PHONE_ID + "/messages");
    }

    @Test
    void graphVersionCanOnlyBeChangedThroughEnvironmentConfiguration() throws Exception {
        environment.put("WHATSAPP_GRAPH_API_VERSION", "v24.0");
        rebuild();
        respond(200, ACCEPTED_RESPONSE);
        mvc.perform(authorizedPost()).andExpect(status().isAccepted());
        assertThat(capturedRequest().getUri().getPath()).isEqualTo("/v24.0/" + PHONE_ID + "/messages");
    }

    @ParameterizedTest
    @ValueSource(ints = {301, 302, 400, 401, 403, 429, 500, 503})
    void providerErrorsAndRedirectsReturnSanitizedFailureWithoutRetries(int code) throws Exception {
        respond(code, "{\"error\":{\"message\":\"must-not-echo-provider-body " + ACCESS_TOKEN + " " + RECIPIENT + "\"}}");
        mvc.perform(authorizedPost()).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("provider_error"))
                .andExpect(jsonPath("$.outgoingMessageId").doesNotExist())
                .andExpect(content().string(not(containsString("must-not-echo-provider-body"))));
        capturedRequest();
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not JSON", "{}", "{} {}", "null", "[]",
            "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":42}]}",
            "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"15555550123\"}]}",
            "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.bad\\nvalue\"}]}",
            "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.15555550123\"}]}",
            "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.synthetic-meta-access-token\"}]}",
            "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.synthetic-test-send-secret\"}]}",
            "{\"messaging_product\":\"whatsapp\",\"error\":{},\"messages\":[{\"id\":\"wamid.example\"}]}"})
    void unexpectedSuccessResponseNeverLeaksRawProviderData(String response) throws Exception {
        respond(200, response);
        mvc.perform(authorizedPost()).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("provider_error"))
                .andExpect(jsonPath("$.outgoingMessageId").doesNotExist());
        capturedRequest();
    }

    @Test
    void boundsProviderResponseAndRejectsMissingResponseEntity() throws Exception {
        respond(200, "x".repeat(64 * 1024 + 1));
        mvc.perform(authorizedPost()).andExpect(status().isBadGateway());
        respond(200, null);
        mvc.perform(authorizedPost()).andExpect(status().isBadGateway());
    }

    @Test
    void timeoutsDoNotRetryAndDoNotClaimTheMessageWasRejectedOrDelivered() throws Exception {
        client.failure = new SocketTimeoutException("must-not-echo-provider-body " + ACCESS_TOKEN + " " + RECIPIENT);
        mvc.perform(authorizedPost()).andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.status").value("timeout"))
                .andExpect(jsonPath("$.message").value("Meta request timed out; acceptance is unknown. No automatic retry was attempted."));
        capturedRequest();
    }

    @Test
    void connectionOrPoolTimeoutIsAlsoSanitized() throws Exception {
        client.failure = new InterruptedIOException("must-not-echo-provider-body " + ACCESS_TOKEN);
        mvc.perform(authorizedPost()).andExpect(status().isGatewayTimeout());
        capturedRequest();
    }

    @Test
    void transportFailureDoesNotRetryOrExposeExceptionDetails() throws Exception {
        client.failure = new IOException("must-not-echo-provider-body " + SECRET + " " + RECIPIENT);
        mvc.perform(authorizedPost()).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.status").value("transport_error"))
                .andExpect(content().string(not(containsString("must-not-echo-provider-body"))));
        capturedRequest();
    }

    private void respond(int status, String body) {
        client.responseStatus = status;
        client.responseBody = body;
    }

    private HttpPost capturedRequest() throws Exception {
        assertThat(client.requests).hasSize(1);
        return (HttpPost) client.requests.get(0);
    }

    /** Supplies HTTP responses in memory. No socket-capable execution path is permitted. */
    private static final class StubHttpClient extends CloseableHttpClient {
        private final List<ClassicHttpRequest> requests = new ArrayList<>();
        private int responseStatus;
        private String responseBody;
        private IOException failure;

        @Override
        public <T> T execute(ClassicHttpRequest request, HttpClientResponseHandler<? extends T> handler) throws IOException {
            requests.add(request);
            if (failure != null) throw failure;
            if (responseStatus == 0) throw new AssertionError("Unexpected HTTP request");
            try (var response = new BasicClassicHttpResponse(responseStatus)) {
                if (responseBody != null) response.setEntity(new StringEntity(responseBody, ContentType.APPLICATION_JSON));
                return handler.handleResponse(response);
            } catch (HttpException e) {
                throw new IOException("Stub response handler failed.", e);
            }
        }

        @Override
        protected CloseableHttpResponse doExecute(HttpHost host, ClassicHttpRequest request, HttpContext context) {
            throw new AssertionError("Network execution is forbidden in these tests");
        }

        @Override
        public void close() { }

        @Override
        public void close(CloseMode closeMode) { }
    }
}
