package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Standalone MVC, mocked persistence, no application context or provider. */
class ReportReviewControllerTest {
    private static final String BASE="/api/whatsapp/reporting/admin";
    private static final String ID="00000000-0000-4000-8000-000000000001";
    private static final String KEY="synthetic-review-only-credential-32";
    private static final String HEADER="X-WhatsApp-Reporting-Admin-Secret";
    private ReportReviewService reviews;private ReportingResolutionService resolution;private MockMvc mvc;
    private MockMvc mvc(String secret) {return MockMvcBuilders.standaloneSetup(new ReportReviewController(reviews,resolution,new ReportingSettings(k->k.equals("WHATSAPP_REPORTING_ADMIN_SECRET")?secret:null),new ObjectMapper().findAndRegisterModules())).build();}
    @BeforeEach void setup(){reviews=mock(ReportReviewService.class);resolution=mock(ReportingResolutionService.class);mvc=mvc(KEY);}
    @ParameterizedTest @ValueSource(strings={"/reviews","/reviews/00000000-0000-4000-8000-000000000001","/review-masters"})
    void allReadsRequireOneCredential(String route) throws Exception {
        mvc.perform(get(BASE+route)).andExpect(status().isForbidden());
        mvc.perform(get(BASE+route).header(HEADER,"wrong")).andExpect(status().isForbidden());
        mvc.perform(get(BASE+route).header(HEADER,KEY,KEY)).andExpect(status().isForbidden());verifyNoInteractions(reviews,resolution);
    }
    @ParameterizedTest @ValueSource(strings={"/reviews/00000000-0000-4000-8000-000000000001/approve","/reviews/00000000-0000-4000-8000-000000000001/entries/1/resolve"})
    void mutationsCannotBypassCredentialThroughBodyOrQuery(String route) throws Exception {
        mvc.perform(post(BASE+route).param("secret",KEY).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(BASE+route).header(HEADER,KEY).param("workerId","7").contentType("application/json").content("{}")).andExpect(status().isBadRequest());verifyNoInteractions(reviews,resolution);
    }
    @ParameterizedTest @ValueSource(strings={"","   "}) void missingCredentialFailsClosed(String key) throws Exception {mvc(key).perform(get(BASE+"/reviews").header(HEADER,KEY)).andExpect(status().isServiceUnavailable());verifyNoInteractions(reviews);}
    @ParameterizedTest @ValueSource(strings={"workerId","recipient","access_token","limit"}) void unknownQueryRejected(String field) throws Exception {mvc.perform(get(BASE+"/reviews").header(HEADER,KEY).param(field,"1")).andExpect(status().isBadRequest());verifyNoInteractions(reviews);}
    @Test void repeatedQueriesAndMalformedPaginationRejectBeforePersistence() throws Exception {
        mvc.perform(get(BASE+"/reviews").header(HEADER,KEY).param("page","0","1")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE+"/reviews").header(HEADER,KEY).param("page","-1")).andExpect(status().isBadRequest());verifyNoInteractions(reviews);
    }
    @ParameterizedTest @ValueSource(strings={"{", "{} {}", "{\"outcome\":\"WORKED\",\"outcome\":\"DID_NOT_WORK\"}","{\"workerId\":7}","{\"expectedRevision\":\"0\",\"outcome\":\"WORKED\",\"masterWorksiteIds\":[11],\"includedEntryIds\":[11],\"removeExistingConfirmed\":false}"})
    void malformedDuplicateAndOverrideBodiesReject(String body) throws Exception {
        mvc.perform(post(BASE+"/reviews/"+ID+"/approve").header(HEADER,KEY).contentType("application/json").content(body)).andExpect(status().isBadRequest());verifyNoInteractions(reviews,resolution);
    }
    @Test void exactDecimalTextAndTypedScopeReachService() throws Exception {
        String body="{\"expectedRevision\":4,\"outcome\":\"WORKED\",\"masterWorksiteIds\":[11],\"includedEntryIds\":[11],\"overtimeHours\":\"1.5\",\"regular\":{\"mode\":\"CUSTOM\",\"hours\":[{\"masterWorksiteId\":11,\"hours\":\"8\"}]},\"removeExistingConfirmed\":false}";
        mvc.perform(post(BASE+"/reviews/"+ID+"/approve").header(HEADER,KEY).contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"));
        var capture=org.mockito.ArgumentCaptor.forClass(ReportReviewService.Decision.class);verify(reviews).approve(eq(ID),capture.capture());Assertions.assertEquals(new java.math.BigDecimal("1.5"),capture.getValue().overtimeHours());
    }
    @Test void resolutionFieldsStrictAndCreationUsesExistingService() throws Exception {
        mvc.perform(post(BASE+"/reviews/"+ID+"/entries/1/resolve").header(HEADER,KEY).contentType("application/json").content("{\"create\":{\"id\":8,\"approvedName\":\"site\"}}")).andExpect(status().isBadRequest());
        mvc.perform(post(BASE+"/reviews/"+ID+"/entries/1/resolve").header(HEADER,KEY).contentType("application/json").content("{\"masterWorksiteId\":11}")).andExpect(status().isOk());verify(resolution).resolve(ID,1,11L,null);
    }
}
