// src/main/java/life/work/IntFit/backend/service/InvoiceReceivingService.java
package life.work.IntFit.backend.service;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import life.work.IntFit.backend.dto.InvoiceReceivingDTO;
import life.work.IntFit.backend.dto.InvoiceReceivingItemDTO;
import life.work.IntFit.backend.exception.error.NotFoundException;
import life.work.IntFit.backend.model.entity.Invoice;
import life.work.IntFit.backend.model.entity.InvoiceItem;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.model.entity.Worksite;
import life.work.IntFit.backend.repository.InvoiceRepository;
import life.work.IntFit.backend.repository.MasterWorksiteRepository;
import life.work.IntFit.backend.repository.WorksiteRepository;
import lombok.RequiredArgsConstructor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.List;
// add these imports
import com.openhtmltopdf.bidi.support.ICUBidiReorderer;
import com.openhtmltopdf.bidi.support.ICUBidiSplitter;

@Service
@RequiredArgsConstructor
public class InvoiceReceivingService {

    private final InvoiceRepository invoiceRepo;
    private final WorksiteRepository worksiteRepo;           // kept if you need it elsewhere
    private final MasterWorksiteRepository masterRepo;

    private static final ZoneId HEBRON_TZ = ZoneId.of("Asia/Hebron");
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    /** Build sanitized, price-free DTO from Invoice entity. */
    @Transactional(readOnly = true)
    public InvoiceReceivingDTO loadSanitized(Long invoiceId) {
        Invoice inv = invoiceRepo.findById(invoiceId)
                .orElseThrow(() -> new NotFoundException("Invoice not found: " + invoiceId));

        // Worksite (may be null)
        Worksite ws = inv.getWorksite();
        Long wsId = (ws != null ? ws.getId() : null);
        String wsName = inv.getWorksiteName();

        // Resolve master
        Long masterId = null;
        String masterName = null;

        if (ws != null) {
            // Try getMasterWorksiteId()
            masterId = tryGetLong(ws, "getMasterWorksiteId");
            if (masterId == null) {
                // Try getMasterWorksite().getId()
                Object mw = getMasterWorksiteViaReflection(ws);
                if (mw != null) masterId = tryGetLong(mw, "getId");
            }
        }

        if (masterId != null) {
            MasterWorksite m = masterRepo.findById(masterId).orElse(null);
            if (m != null) {
                // Prefer approvedName if present
                masterName = tryGetString(m, "getApprovedName");
                if (!StringUtils.hasText(masterName)) {
                    masterName = tryGetString(m, "getName");
                }
            }
        }
        if (!StringUtils.hasText(masterName)) masterName = wsName; // fallback to child name

        // Items (NO PRICES)
        List<InvoiceReceivingItemDTO> items = new ArrayList<>();
        List<InvoiceItem> invItems = inv.getItems() == null ? List.of() : inv.getItems();
        int idx = 1;
        for (InvoiceItem it : invItems) {
            items.add(InvoiceReceivingItemDTO.builder()
                    .index(idx++)
                    .name(s(it.getDescription()))
                    .qty(num(it.getQuantity()))
                    .unit("قطعة") // fallback; upgrade later if you add unit
                    .build());
        }

        return InvoiceReceivingDTO.builder()
                .invoiceId(inv.getId())
                .date(inv.getDate()) // LocalDateTime
                .masterWorksiteId(masterId)
                .masterWorksiteName(s(masterName))
                .worksiteName(s(wsName))
                .items(items)
                .build();
    }

    /** Render sanitized DTO → list of page images (width 1080px). */
    public List<BufferedImage> renderImages(InvoiceReceivingDTO dto, String lang) {
        String html = buildHtml(dto, lang);
        byte[] pdf = htmlToPdf(html);
        return pdfToImagesScaled(pdf);
    }

    // ---------- Internals ----------

    private String buildHtml(InvoiceReceivingDTO m, String lang) {
        String dir = "ar".equalsIgnoreCase(lang) ? "rtl" : "ltr";
        String langCode = (lang == null ? "ar" : lang);
        String title = "ar".equalsIgnoreCase(lang) ? "تأكيد استلام مواد"
                : "Receiving Checklist – No Prices";

        String dateStr = "";
        if (m.getDate() != null) {
            dateStr = m.getDate().atZone(HEBRON_TZ).toLocalDate().format(DATE_FMT);
        }

        String fontReg = resourceUrl("fonts/Cairo-Regular.ttf");
        String fontBold = resourceUrl("fonts/Cairo-Bold.ttf");

        StringBuilder rows = new StringBuilder();
        for (InvoiceReceivingItemDTO it : m.getItems()) {
            rows.append("<tr>")
                    .append("<td class='idx'>").append(it.getIndex()).append("</td>")
                    .append("<td class='name'>").append(esc(it.getName())).append("</td>")
                    .append("<td class='qty'>").append(fmtQty(it.getQty())).append("</td>")
//                    .append("<td class='unit'>").append(esc(it.getUnit())).append("</td>")
                    .append("</tr>\n");
        }

        return "<html lang='" + langCode + "' dir='" + dir + "'>\n" +
                "<head>\n" +
                "  <meta charset=\"utf-8\"/>\n" +
                "  <style>\n" +
                "    @font-face { font-family:'Cairo'; src:url('" + fontReg + "'); font-weight:400; }\n" +
                "    @font-face { font-family:'Cairo'; src:url('" + fontBold + "'); font-weight:700; }\n" +
                "    @page { size:A4; margin:24px; }\n" +
                "    body { font-family:'Cairo', sans-serif; color:#111; }\n" +
                "    .title { font-size:28px; font-weight:700; margin-bottom:8px; }\n" +
                "    .meta  { font-size:18px; color:#444; margin-bottom:8px; }\n" +
                "    .note  { font-size:14px; color:#666; margin-bottom:10px; }\n" +
                "    table { width:100%; border-collapse:collapse; table-layout:fixed; }\n" +
                "    th,td { border:1px solid #ddd; padding:8px; font-size:18px; }\n" +
                "    th { background:#f6f6f6; }\n" +
                "    .idx  { width:6%; text-align:center; }\n" +
                "    .name { width:58%; word-wrap:break-word; }\n" +
                "    .qty  { width:18%; text-align:center; }\n" +
                "    .unit { width:18%; text-align:center; }\n" +
                "  </style>\n" +
                "</head>\n" +
                "<body>\n" +
                "  <div class=\"title\">" + esc(title) + "</div>\n" +
                "  <div class=\"meta\">الموقع: <b>" + esc(nz(m.getMasterWorksiteName())) + "</b> | " +
                "التاريخ: " + esc(nz(dateStr)) + "</div>\n" +
                "  <div class=\"meta\">رقم الفاتورة: " + (m.getInvoiceId() == null ? "-" : m.getInvoiceId()) + "</div>\n" +
                "  <table>\n" +
                "    <thead>\n" +
                "      <tr>\n" +
                "        <th class=\"idx\">#</th>\n" +
                "        <th class=\"name\">الصنف</th>\n" +
                "        <th class=\"qty\">الكمية</th>\n" +
                "      </tr>\n" +
                "    </thead>\n" +
                "    <tbody>\n" +
                rows +
                "    </tbody>\n" +
                "  </table>\n" +
                "</body>\n" +
                "</html>\n";
    }

    private byte[] htmlToPdf(String html) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            PdfRendererBuilder b = new PdfRendererBuilder();
            b.useFastMode();

            // ✅ Enable Arabic bidi/shaping (requires openhtmltopdf-rtl-support + icu4j)
            b.useUnicodeBidiSplitter(new ICUBidiSplitter.ICUBidiSplitterFactory());
            b.useUnicodeBidiReorderer(new ICUBidiReorderer());

            b.withHtmlContent(html, null);
            b.toStream(baos);
            b.run();
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException("Failed to render PDF", e);
        }
    }




    private List<BufferedImage> pdfToImagesScaled(byte[] pdfBytes) {
        List<BufferedImage> pages = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                BufferedImage img = renderer.renderImageWithDPI(i, 150, ImageType.RGB);
                if (img.getWidth() != 1080) {
                    int th = (int) Math.round(img.getHeight() * (1080 / (double) img.getWidth()));
                    Image scaled = img.getScaledInstance(1080, th, Image.SCALE_SMOOTH);
                    BufferedImage bi = new BufferedImage(1080, th, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g = bi.createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g.drawImage(scaled, 0, 0, null);
                    g.dispose();
                    pages.add(bi);
                } else {
                    pages.add(img);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to rasterize PDF", e);
        }
        return pages;
    }

    // ---------- reflection helpers ----------
    private static Long tryGetLong(Object obj, String getter) {
        try {
            Method m = obj.getClass().getMethod(getter);
            Object v = m.invoke(obj);
            if (v instanceof Number) return ((Number) v).longValue();
        } catch (Exception ignored) {}
        return null;
    }
    private static String tryGetString(Object obj, String getter) {
        try {
            Method m = obj.getClass().getMethod(getter);
            Object v = m.invoke(obj);
            return v == null ? null : String.valueOf(v);
        } catch (Exception ignored) {}
        return null;
    }
    private static Object getMasterWorksiteViaReflection(Object ws) {
        try {
            Method m = ws.getClass().getMethod("getMasterWorksite");
            return m.invoke(ws);
        } catch (Exception ignored) {}
        return null;
    }

    // ---------- utils ----------
    private static String esc(String s){ return s==null? "": s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;"); }
    private static String s(String v){ return v==null? "" : v; }
    private static String nz(String v){ return StringUtils.hasText(v)? v : "-"; }
    private static double num(Number n){ return n==null? 0.0 : n.doubleValue(); }
    private static String fmtQty(double d){ return (Math.floor(d)==d)? String.valueOf((long)d) : String.format(Locale.US,"%.1f", d); }
    private static String resourceUrl(String path){
        URL u = Thread.currentThread().getContextClassLoader().getResource(path);
        return u!=null? u.toExternalForm(): "";
    }
}
