package life.work.IntFit.backend.reporting;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.format.DateTimeFormatter;
import java.util.*;
import static life.work.IntFit.backend.reporting.ReportingModel.*;

/** Pure payload/choice builder. No existing worker DTO (and hence no contact/payroll data) reaches WhatsApp. */
final class ReportingFlow {
    private final ObjectMapper mapper;
    ReportingFlow(ObjectMapper mapper) { this.mapper=mapper; }
    static String token(Request r,String action,long id,int page) {
        return "wr:"+r.id()+":"+r.revision()+":"+action+":"+id+":"+page;
    }
    static Action action(String value) {
        if (value == null || !value.matches("wr:[0-9a-f-]{36}:[0-9]{1,9}:(start|none|site|cow|done|finish|submit|back|page):[0-9]{1,18}:[0-9]{1,6}")) return null;
        try {
            String[] a=value.split(":");
            return new Action(UUID.fromString(a[1]).toString(),Long.parseLong(a[2]),a[3],Long.parseLong(a[4]),Integer.parseInt(a[5]));
        } catch (RuntimeException ignored) { return null; }
    }
    record Action(String requestId,long revision,String verb,long id,int page) { }
    String template(Request r,String language) {
        String day=r.workDate().format(DateTimeFormatter.ofPattern("EEEE، dd/MM/uuuu",Locale.forLanguageTag("ar")));
        return json(Map.of("messaging_product","whatsapp","to",r.recipient(),"type","template","template",Map.of(
                "name","daily_work_report_ar","language",Map.of("code",language),"components",List.of(
                        Map.of("type","body","parameters",List.of(Map.of("type","text","text",day))),
                        Map.of("type","button","sub_type","quick_reply","index","0","parameters",List.of(Map.of("type","payload","payload",token(r,"start",0,0)))),
                        Map.of("type","button","sub_type","quick_reply","index","1","parameters",List.of(Map.of("type","payload","payload",token(r,"none",0,0))))))));
    }
    String prompt(Request r,List<Site> sites,List<Coworker> mentions,List<Choice> availableSites,List<Choice> coworkers,int page) {
        String date=r.workDate().format(DateTimeFormatter.ofPattern("EEEE، dd/MM/uuuu",Locale.forLanguageTag("ar")));
        Map<String,Object> interactive;
        switch (r.step()) {
            case "START" -> interactive=buttons("أين عملت يوم "+date+"؟",r,new String[][]{{"start","تسجيل مواقع العمل"},{"none","لم أعمل"}});
            case "SITES" -> {
                Set<Long> selected=new HashSet<>(); sites.forEach(s -> selected.add(s.siteId()));
                List<Choice> remaining=sites.size()>=50 ? List.of() : availableSites.stream().filter(s -> !selected.contains(s.id())).toList();
                interactive=list(r,"اختر موقع العمل ليوم "+date+". يمكنك تسجيل أكثر من موقع. المواقع المسجلة: "+sites.size(),
                        remaining,"site",page,sites.isEmpty() ? null : new Choice(0,"انتهيت من المواقع"),"finish",Set.of());
            }
            case "COWORKERS" -> {
                Set<Long> selected=new HashSet<>();
                mentions.stream().filter(c -> Objects.equals(r.currentSite(),c.siteId())).forEach(c -> selected.add(c.workerId()));
                String site=sites.stream().filter(s -> Objects.equals(r.currentSite(),s.siteId())).map(Site::label).findFirst().orElse("");
                interactive=list(r,"من عمل معك في "+label(site,100)+"؟ الاختيار اختياري. اضغط الاسم لتحديده أو إلغاء تحديده، ثم متابعة.",
                        coworkers,"cow",page,new Choice(0,"متابعة بدون أسماء أخرى"),"done",selected);
            }
            case "CONFIRM_WORK" -> interactive=buttons("تأكيد تقرير يوم "+date+": سجلت "+sites.size()+" موقع/مواقع و"+mentions.size()+
                    " إشارة لزملاء. المواقع: "+label(String.join("، ",sites.stream().map(Site::label).toList()),550)+
                    ". هل تريد إرسال التقرير للمراجعة؟ الساعات يحددها المسؤول، وإشارات الزملاء معلومات مساعدة فقط.",r,
                    new String[][]{{"submit","إرسال للمراجعة"},{"back","عودة للمواقع"}});
            case "CONFIRM_NONE" -> interactive=buttons("تأكيد: لم أعمل يوم "+date+". هل تريد إرسال التقرير للمراجعة؟",r,
                    new String[][]{{"submit","إرسال للمراجعة"},{"back","رجوع"}});
            case "SUBMITTED" -> {
                return json(Map.of("messaging_product","whatsapp","to",r.recipient(),"type","text",
                        "text",Map.of("preview_url",false,"body","تم إرسال تقرير يوم "+date+"، وهو بانتظار مراجعة المسؤول. لم يتم تغيير أي تعيينات أو ساعات عمل.")));
            }
            default -> throw new IllegalStateException("Unknown reporting step");
        }
        return json(Map.of("messaging_product","whatsapp","to",r.recipient(),"type","interactive","interactive",interactive));
    }
    private static Map<String,Object> buttons(String text,Request r,String[][] actions) {
        List<Object> buttons=new ArrayList<>();
        for (String[] a:actions) buttons.add(Map.of("type","reply","reply",Map.of("id",token(r,a[0],0,0),"title",a[1])));
        return Map.of("type","button","body",Map.of("text",text),"action",Map.of("buttons",buttons));
    }
    private static Map<String,Object> list(Request r,String text,List<Choice> choices,String verb,int requestedPage,
                                           Choice end,String endVerb,Set<Long> selected) {
        int pages=Math.max(1,(choices.size()+6)/7);
        int page=Math.min(Math.max(0,requestedPage),pages-1);
        List<Object> rows=new ArrayList<>();
        for (Choice c:choices.subList(page*7,Math.min(choices.size(),page*7+7)))
            rows.add(Map.of("id",token(r,verb,c.id(),page),"title",label((selected.contains(c.id()) ? "✓ " : "")+c.label(),24)));
        if (page>0) rows.add(Map.of("id",token(r,"page",0,page-1),"title","الصفحة السابقة"));
        if (page+1<pages) rows.add(Map.of("id",token(r,"page",0,page+1),"title","الصفحة التالية"));
        if (end!=null) rows.add(Map.of("id",token(r,endVerb,0,0),"title",end.label()));
        // A site can be withdrawn between prompts; provide a harmless navigation choice, never a free-text fallback.
        if (rows.isEmpty()) rows.add(Map.of("id",token(r,"page",0,0),"title","تحديث القائمة"));
        return Map.of("type","list","body",Map.of("text",text),"action",Map.of("button","عرض الخيارات",
                "sections",List.of(Map.of("title","الخيارات","rows",rows))));
    }
    boolean issued(String payload,String token) {
        try { return contains(mapper.readTree(payload),token); }
        catch (JsonProcessingException ignored) { return false; }
    }
    private static boolean contains(JsonNode node,String token) {
        if (node.isTextual()) return token.equals(node.textValue());
        for (JsonNode child:node) if (contains(child,token)) return true;
        return false;
    }
    /** Remove controls/formatting, bound display labels; only name or approved site label is supplied. */
    static String label(String s,int max) {
        if (s==null) return "—";
        StringBuilder b=new StringBuilder();
        s.replaceAll("(?<![A-Za-z0-9])\\+?[0-9][0-9 ().-]{6,}[0-9](?![A-Za-z0-9])","[رقم محجوب]")
                .codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c)!=Character.FORMAT
                && Character.getType(c)!=Character.LINE_SEPARATOR && Character.getType(c)!=Character.PARAGRAPH_SEPARATOR)
                .limit(max).forEach(b::appendCodePoint);
        return b.isEmpty() ? "—" : b.toString();
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Could not build reporting message"); }
    }
}
