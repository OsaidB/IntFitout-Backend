package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.*;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.http.message.BasicClassicHttpResponse;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.io.*;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class MetaReportingSenderTest {
    Map<String,String> env;
    ObjectMapper mapper=new ObjectMapper();
    StubHttp http;
    MetaReportingSender sender;
    String payload;
    @BeforeEach void setup() {
        env=ReportingServiceTest.environment();http=new StubHttp();build();
        var r=new ReportingModel.Request("00000000-0000-0000-0000-000000000001",7,"970599000007","123400007",LocalDate.of(2026,10,22),"START",0,null,null,Instant.EPOCH,null,null);
        payload=new ReportingFlow(mapper).template(r,"ar");
    }
    void build(){sender=new MetaReportingSender(new ReportingSettings(env::get),mapper,http);}
    @Test void exactRealSenderEndpointTemplateAndTimeoutsWithOneAttempt() throws Exception {
        var result=sender.send(payload);assertEquals("ACCEPTED",result.state());assertEquals("wamid.mockAccepted",result.metaId());assertEquals(1,http.calls);
        assertEquals("https://graph.facebook.com/v25.0/123400007/messages",http.uri);assertFalse(http.uri.contains("999900009"));
        var node=mapper.readTree(http.body);assertEquals("970599000007",node.path("to").asText());assertEquals("daily_work_report_ar",node.path("template").path("name").asText());
        assertTrue(http.bounded);assertEquals("Bearer "+env.get("WHATSAPP_ACCESS_TOKEN"),http.authorization);
    }
    @ParameterizedTest @ValueSource(ints={400,401,429,500,503}) void providerErrorsAreSanitizedAndNeverRetried(int code) {
        http.code=code;http.response="synthetic-secret-and-phone-provider-body";var result=sender.send(payload);
        assertEquals("REJECTED",result.state());assertNull(result.metaId());assertEquals(1,http.calls);assertFalse(result.toString().contains(http.response));
    }
    @ParameterizedTest @ValueSource(strings={"", "not json", "{}", "{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"invalid\"}]}","{\"error\":\"do-not-echo\"}","{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.970599000007\"}]}"})
    void invalidSuccessIsUncertainWithoutExposingRawResponse(String body) {
        http.response=body;assertEquals("UNCERTAIN",sender.send(payload).state());assertEquals(1,http.calls);
    }
    @Test void largeResponseIsUncertain() {http.response=" ".repeat(65537);assertEquals("UNCERTAIN",sender.send(payload).state());}
    @Test void timeoutAndTransportFailureAreUncertainOneAttempt() {
        http.failure=new SocketTimeoutException("never log this");assertEquals("UNCERTAIN",sender.send(payload).state());assertEquals(1,http.calls);
        http.failure=new IOException("never log this");assertEquals("UNCERTAIN",sender.send(payload).state());assertEquals(2,http.calls);
    }
    @Test void disabledOrWrongRecipientCannotReachTransport() {
        env.remove("WHATSAPP_REPORTING_SEND_ENABLED");build();assertEquals("REJECTED",sender.send(payload).state());assertEquals(0,http.calls);
        env.put("WHATSAPP_REPORTING_SEND_ENABLED","true");build();assertEquals("REJECTED",sender.send(payload.replace("970599000007","972599000007")).state());assertEquals(0,http.calls);
    }
    @Test void managementAndAdditionalWorkersUseSameTransportOnlyWithExplicitRoutingAndGates() {
        env.putAll(DailyReportingTest.dailyEnvironment());build();
        String management="{\"messaging_product\":\"whatsapp\",\"to\":\"970599000099\",\"type\":\"template\",\"template\":{\"name\":\"daily_worker_dispatch_summary_ar\",\"language\":{\"code\":\"ar\"}}}";
        var o=new ReportingModel.Outbound("id",null,0,"MGMT_SUMMARY",management,"SENDING",null,null,Instant.EPOCH,"MANAGEMENT","OWNER","970599000099","123400007","run",null);
        assertEquals("ACCEPTED",sender.send(o).state());assertEquals(1,http.calls);assertTrue(http.bounded);
        env.put("WHATSAPP_REPORTING_MANAGEMENT_TEMPLATE_CONFIRMED","false");build();assertEquals("REJECTED",sender.send(o).state());assertEquals(1,http.calls);
        env.put("WHATSAPP_REPORTING_MANAGEMENT_TEMPLATE_CONFIRMED","true");build();assertEquals("REJECTED",sender.send(o.routed("970599000098","123400007","run")).state());
        assertEquals("REJECTED",sender.send(o.routed("970599000099","999900009","run")).state());assertEquals(1,http.calls);
        String worker=payload.replace("970599000007","972599000008");var w=new ReportingModel.Outbound("worker","request",0,"TEMPLATE",worker,"SENDING",null,null,Instant.EPOCH).routed("972599000008","123400007",null);
        assertEquals("ACCEPTED",sender.send(w).state());env.put("WHATSAPP_REPORTING_ROLLOUT_ENABLED","false");build();assertEquals("REJECTED",sender.send(w).state());assertEquals(2,http.calls);
    }
    static final class StubHttp extends CloseableHttpClient {
        int calls,code=200;String uri,body,authorization;
        String response="{\"messaging_product\":\"whatsapp\",\"messages\":[{\"id\":\"wamid.mockAccepted\"}]}";
        IOException failure;boolean bounded;
        @Override public <T>T execute(ClassicHttpRequest request,HttpClientResponseHandler<? extends T> handler) throws IOException {
            calls++;try{uri=request.getUri().toString();}catch(Exception e){throw new AssertionError(e);}
            authorization=request.getFirstHeader(HttpHeaders.AUTHORIZATION).getValue();body=new String(request.getEntity().getContent().readAllBytes(),StandardCharsets.UTF_8);
            var config=((org.apache.hc.client5.http.classic.methods.HttpPost)request).getConfig();
            bounded=config.getResponseTimeout().toMilliseconds()==10000 && config.getConnectionRequestTimeout().toMilliseconds()==3000;
            if(failure!=null)throw failure;
            BasicClassicHttpResponse reply=new BasicClassicHttpResponse(code);reply.setEntity(new StringEntity(response,ContentType.APPLICATION_JSON));
            try{return handler.handleResponse(reply);}catch(HttpException e){throw new IOException(e);}
        }
        @Override protected CloseableHttpResponse doExecute(HttpHost host,ClassicHttpRequest request,HttpContext context){throw new AssertionError("Real networking forbidden");}
        @Override public void close(){}@Override public void close(CloseMode mode){}
    }
}
