package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.time.Clock;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ReportingAdminControllerTest {
    static final String BASE="/api/whatsapp/reporting/admin";
    static final String HEADER="X-WhatsApp-Reporting-Admin-Secret";
    Map<String,String> env;
    MemoryReportingStore store;
    MockMvc mvc;
    @BeforeEach void setup() { env=ReportingServiceTest.environment();store=new MemoryReportingStore();build(); }
    void build() {
        ReportingSettings settings=new ReportingSettings(env::get);
        ReportingService service=new ReportingService(store,settings,p -> {fail("No test can send");return null;},Clock.systemUTC(),new ObjectMapper());
        mvc=MockMvcBuilders.standaloneSetup(new ReportingAdminController(settings,service)).build();
    }
    MockHttpServletRequestBuilder route(String key) {
        String id="00000000-0000-0000-0000-000000000001";
        return switch(key) {
            case "status" -> get(BASE+"/status");
            case "workers" -> put(BASE+"/workers/7").contentType(MediaType.APPLICATION_JSON).content("{\"eligible\":true,\"whatsappNumber\":\"+970599000007\"}");
            case "worksites" -> put(BASE+"/worksites/11").contentType(MediaType.APPLICATION_JSON).content("{\"selectable\":true}");
            case "initiate" -> post(BASE+"/requests").contentType(MediaType.APPLICATION_JSON).content("{\"workDate\":\"2026-10-22\"}");
            case "reports" -> get(BASE+"/requests");
            case "report" -> get(BASE+"/requests/"+id);
            case "dispatch" -> post(BASE+"/outbound/"+id+"/dispatch");
            case "resolve" -> post(BASE+"/outbound/"+id+"/resolve").contentType(MediaType.APPLICATION_JSON).content("{\"decision\":\"abandon\"}");
            case "entry" -> post(BASE+"/requests/"+id+"/entries/1/resolve").contentType(MediaType.APPLICATION_JSON).content("{\"masterWorksiteId\":11}");
            default -> throw new AssertionError();
        };
    }
    @ParameterizedTest @ValueSource(strings={"status","workers","worksites","initiate","reports","report","dispatch","resolve","entry"})
    void allRoutesRejectMissingWrongAndDuplicateCredentialsWithoutTouchingStore(String route) throws Exception {
        mvc.perform(route(route)).andExpect(status().isForbidden());
        mvc.perform(route(route).header(HEADER,"wrong-secret")).andExpect(status().isForbidden());
        mvc.perform(route(route).header(HEADER,env.get("WHATSAPP_REPORTING_ADMIN_SECRET"),"duplicate")).andExpect(status().isForbidden());
        assertEquals(0,store.transactions);
    }
    @ParameterizedTest @ValueSource(strings={"status","workers","worksites","initiate","reports","report","dispatch","resolve","entry"})
    void everyRouteFailsClosedWithMissingAdminConfiguration(String route) throws Exception {
        env.remove("WHATSAPP_REPORTING_ADMIN_SECRET");build();
        mvc.perform(route(route).header(HEADER,"anything")).andExpect(status().isServiceUnavailable());assertEquals(0,store.transactions);
    }
    @Test void noConfigStatusIsAvailableOnlyWithValidDedicatedCredentialAndContainsNoSecrets() throws Exception {
        String secret=env.get("WHATSAPP_REPORTING_ADMIN_SECRET");env.clear();env.put("WHATSAPP_REPORTING_ADMIN_SECRET",secret);build();
        mvc.perform(get(BASE+"/status").header(HEADER,secret)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control","no-store")).andExpect(jsonPath("$.sendingEnabled").value(false))
                .andExpect(jsonPath("$.pilotConfigurationReady").value(false));assertEquals(0,store.transactions);
    }
    @Test void credentialInQueryDoesNotAuthorizeAndNoRequestOverridesAreAccepted() throws Exception {
        mvc.perform(get(BASE+"/status").queryParam("secret",env.get("WHATSAPP_REPORTING_ADMIN_SECRET"))).andExpect(status().isForbidden());
        mvc.perform(get(BASE+"/status").header(HEADER,env.get("WHATSAPP_REPORTING_ADMIN_SECRET")).queryParam("recipient","970599000008")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE+"/requests").header(HEADER,env.get("WHATSAPP_REPORTING_ADMIN_SECRET")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"recipient\":\"970599000008\",\"workDate\":\"2026-10-22\"}")).andExpect(status().isBadRequest());assertEquals(0,store.transactions);
    }
    @Test void workerAndSiteConfigurationIsExplicitAndValidated() throws Exception {
        store.workers.put(7L,new ReportingModel.Worker(7,"عامل",false,null));store.choices.put(11L,new ReportingModel.Choice(11,"موقع"));
        mvc.perform(route("workers").header(HEADER,env.get("WHATSAPP_REPORTING_ADMIN_SECRET"))).andExpect(status().isOk());
        assertEquals("970599000007",store.workers.get(7L).number());assertTrue(store.workers.get(7L).eligible());
        mvc.perform(route("worksites").header(HEADER,env.get("WHATSAPP_REPORTING_ADMIN_SECRET"))).andExpect(status().isOk());assertTrue(store.selectable.contains(11L));
    }
}
