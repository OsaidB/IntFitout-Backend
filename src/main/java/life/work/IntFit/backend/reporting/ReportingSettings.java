package life.work.IntFit.backend.reporting;

import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.function.Function;

/** Environment only. Intentionally no generated toString containing secrets. */
@Component
public final class ReportingSettings {
    final boolean enabled, sendEnabled, templateConfirmed;
    final String adminSecret, accessToken, phoneId, recipient, language, version;
    final Long workerId;
    final boolean rolloutEnabled,dailyEnabled,managementTemplateConfirmed;
    final String managementLanguage,ownerRecipient,fatherRecipient;

    public ReportingSettings() { this(System::getenv); }
    public ReportingSettings(Function<String, String> env) {
        enabled = yes(env.apply("WHATSAPP_REPORTING_ENABLED"));
        rolloutEnabled=yes(env.apply("WHATSAPP_REPORTING_ROLLOUT_ENABLED"));
        dailyEnabled=yes(env.apply("WHATSAPP_REPORTING_DAILY_ENABLED"));
        managementTemplateConfirmed=yes(env.apply("WHATSAPP_REPORTING_MANAGEMENT_TEMPLATE_CONFIRMED"));
        managementLanguage=env.apply("WHATSAPP_REPORTING_MANAGEMENT_TEMPLATE_LANGUAGE");
        ownerRecipient=managementNumber(env.apply("WHATSAPP_REPORTING_MANAGEMENT_OWNER_RECIPIENT"));
        fatherRecipient=managementNumber(env.apply("WHATSAPP_REPORTING_MANAGEMENT_FATHER_RECIPIENT"));
        sendEnabled = yes(env.apply("WHATSAPP_REPORTING_SEND_ENABLED"));
        templateConfirmed = yes(env.apply("WHATSAPP_REPORTING_TEMPLATE_CONFIRMED"));
        adminSecret = env.apply("WHATSAPP_REPORTING_ADMIN_SECRET");
        accessToken = env.apply("WHATSAPP_ACCESS_TOKEN");
        // No fallback to WHATSAPP_PHONE_NUMBER_ID (the connectivity test sender).
        phoneId = env.apply("WHATSAPP_REPORTING_PHONE_NUMBER_ID");
        recipient = env.apply("WHATSAPP_REPORTING_PILOT_RECIPIENT");
        language = env.apply("WHATSAPP_REPORTING_TEMPLATE_LANGUAGE");
        String v = env.apply("WHATSAPP_GRAPH_API_VERSION");
        version = v == null ? "v25.0" : v;
        Long id = null;
        try { id = Long.valueOf(env.apply("WHATSAPP_REPORTING_PILOT_WORKER_ID")); }
        catch (RuntimeException ignored) { }
        workerId = id != null && id > 0 ? id : null;
    }
    private static boolean yes(String v) { return "true".equalsIgnoreCase(v); }
    static boolean secretSafe(String s) {
        return s != null && s.length() >= 24 && s.length() <= 512
                && s.codePoints().allMatch(c -> c >= 33 && c <= 126);
    }
    public boolean adminConfigured() { return secretSafe(adminSecret); }
    public boolean authorized(String supplied) {
        return adminConfigured() && supplied != null && supplied.length() <= 512
                && MessageDigest.isEqual(adminSecret.getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8));
    }
    public boolean receivingReady() {
        return enabled && phoneId != null && phoneId.matches("[0-9]{1,30}") && (rolloutEnabled || pilotReady());
    }
    public boolean sendingReady() {
        return receivingReady() && transportReady() && templateConfirmed
                && language != null && language.matches("ar(?:_[A-Z]{2})?");
    }
    boolean pilotReady() {return workerId!=null && canonicalNumber(recipient);}
    boolean transportReady() {
        return enabled && sendEnabled && adminConfigured() && phoneId!=null && phoneId.matches("[0-9]{1,30}")
                && accessToken != null && !accessToken.isBlank()
                && accessToken.codePoints().allMatch(c -> c >= 33 && c <= 126)
                && version != null && version.matches("v[1-9][0-9]{0,2}\\.[0-9]{1,2}");
    }
    boolean managementReady() {
        return transportReady() && managementTemplateConfirmed && managementLanguage!=null && managementLanguage.matches("ar(?:_[A-Z]{2})?")
                && ownerRecipient!=null && fatherRecipient!=null && !ownerRecipient.equals(fatherRecipient);
    }
    boolean dailyReady() {return dailyEnabled && transportReady() && templateConfirmed && language!=null && language.matches("ar(?:_[A-Z]{2})?") && managementReady();}
    boolean anySendingReady() {return sendingReady() || (dailyEnabled && managementReady());}
    boolean managementNumberMatches(String number) {return number!=null && (number.equals(ownerRecipient) || number.equals(fatherRecipient));}
    boolean allowsWorker(ReportingModel.Worker worker) {
        return worker!=null && worker.eligible() && canonicalNumber(worker.number()) && !managementNumberMatches(worker.number())
                && (rolloutEnabled || (pilotReady() && worker.id()==workerId && recipient.equals(worker.number())));
    }
    java.util.Map<String,String> managementRecipients() {
        if(ownerRecipient==null || fatherRecipient==null || ownerRecipient.equals(fatherRecipient)) return java.util.Map.of();
        return java.util.Map.of("OWNER",ownerRecipient,"FATHER",fatherRecipient);
    }
    private static String managementNumber(String number) {
        try {return normalizeNumber(number);} catch(ReportingProblem e) {return null;}
    }
    static boolean canonicalNumber(String s) { return s != null && s.matches("[1-9][0-9]{6,14}"); }
    /** Accept explicit international digits or a leading + only. Never infer a country prefix. */
    public static String normalizeNumber(String s) {
        if (s == null || s.isBlank()) return null;
        String n = s.startsWith("+") ? s.substring(1) : s;
        if (!canonicalNumber(n)) throw new ReportingProblem(400, "Use an explicit international number, digits with optional leading +; no inferred prefix.");
        return n;
    }
}
