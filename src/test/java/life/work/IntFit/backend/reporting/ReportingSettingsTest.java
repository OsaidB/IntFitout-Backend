package life.work.IntFit.backend.reporting;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static org.junit.jupiter.api.Assertions.*;

class ReportingSettingsTest {
    @Test void disabledDefaultsAndNoImplicitPilotOrTemplateLanguage() {
        var s=new ReportingSettings(k -> null);assertFalse(s.receivingReady());assertFalse(s.sendingReady());assertFalse(s.adminConfigured());
        assertNull(s.workerId);assertNull(s.recipient);assertNull(s.phoneId);assertNull(s.language);assertEquals("v25.0",s.version);
    }
    @ParameterizedTest @CsvSource({
            "WHATSAPP_REPORTING_PILOT_WORKER_ID,-7", "WHATSAPP_REPORTING_PILOT_WORKER_ID,not-an-id",
            "WHATSAPP_REPORTING_PILOT_RECIPIENT,+970599000007", "WHATSAPP_REPORTING_PILOT_RECIPIENT,0599000007",
            "WHATSAPP_REPORTING_PHONE_NUMBER_ID,https://example.invalid", "WHATSAPP_REPORTING_TEMPLATE_LANGUAGE,en_US",
            "WHATSAPP_GRAPH_API_VERSION,v25.0/path", "WHATSAPP_REPORTING_TEMPLATE_CONFIRMED,false",
            "WHATSAPP_REPORTING_SEND_ENABLED,false", "WHATSAPP_REPORTING_ENABLED,false"})
    void malformedOrDisabledSettingsFailClosed(String key,String value) {
        var e=ReportingServiceTest.environment();e.put(key,value);assertFalse(new ReportingSettings(e::get).sendingReady());
    }
    @Test void blankValuesNeverBecomeDefaultsOrFallBackToConnectivitySender() {
        var e=ReportingServiceTest.environment();
        for(String key:new String[]{"WHATSAPP_REPORTING_PHONE_NUMBER_ID","WHATSAPP_REPORTING_PILOT_WORKER_ID","WHATSAPP_REPORTING_PILOT_RECIPIENT","WHATSAPP_REPORTING_TEMPLATE_LANGUAGE","WHATSAPP_ACCESS_TOKEN"}) {
            var changed=new java.util.HashMap<>(e);changed.put(key," ");assertFalse(new ReportingSettings(changed::get).sendingReady());
        }
        e.put("WHATSAPP_REPORTING_ADMIN_SECRET"," ");var s=new ReportingSettings(e::get);assertFalse(s.adminConfigured());assertFalse(s.authorized(" "));assertFalse(s.sendingReady());
        e.remove("WHATSAPP_REPORTING_PHONE_NUMBER_ID");assertFalse(new ReportingSettings(e::get).receivingReady());assertNotNull(e.get("WHATSAPP_PHONE_NUMBER_ID"));
    }
}
