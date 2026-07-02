package life.work.IntFit.backend.utils;

import life.work.IntFit.backend.dto.PendingInvoiceDTO;
import life.work.IntFit.backend.model.entity.PendingInvoice;
import life.work.IntFit.backend.service.PendingInvoiceService;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime; // ✅
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PythonInvoiceProcessor {

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String PYTHON_API_URL = "https://invoices-convertor-1.onrender.com/process-invoice";

    // ✅ In-process persistence (replaces the previous HTTP self-POST to /api/invoices/pending/upload).
    // @Lazy breaks a circular dependency: PendingInvoiceService already depends on PythonInvoiceProcessor.
    private final PendingInvoiceService pendingInvoiceService;

    public PythonInvoiceProcessor(@Lazy PendingInvoiceService pendingInvoiceService) {
        this.pendingInvoiceService = pendingInvoiceService;
    }

    // ✅ CHANGED: accept smsReceivedAt
    public void sendInvoiceToPython(String invoiceUrl, LocalDateTime smsReceivedAt) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Step 1: Send PDF URL to Python tool
        Map<String, String> urlPayload = new HashMap<>();
        urlPayload.put("url", invoiceUrl);

        HttpEntity<Map<String, String>> urlRequest = new HttpEntity<>(urlPayload, headers);

        try {
            ResponseEntity<PendingInvoiceDTO> response = restTemplate.postForEntity(
                    PYTHON_API_URL,
                    urlRequest,
                    PendingInvoiceDTO.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                PendingInvoiceDTO parsedInvoice = response.getBody();

                // ✅ IMPORTANT: store the REAL SMS timestamp (used for uploader cutoffs)
                parsedInvoice.setReceivedAtSms(smsReceivedAt);

                // ✅ Save in-process instead of POSTing back to our own HTTP endpoint.
                pendingInvoiceService.savePendingInvoice(parsedInvoice);
                System.out.println("✅ Pending invoice saved successfully (in-process)");

            } else {
                System.err.println("❌ Python tool returned non-success status: " + response.getStatusCode());
            }

        } catch (Exception e) {
            System.err.println("❌ Exception during Python invoice processing: " + e.getMessage());
        }
    }

    // In PythonInvoiceProcessor.java, reprocessMismatchedInvoices method
    public void reprocessMismatchedInvoices(List<PendingInvoice> invoices) {
        String FIX_MISMATCHED_URL = "https://invoices-convertor-1.onrender.com/fix-mismatched";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (PendingInvoice invoice : invoices) {
            try {
                System.out.println("🔁 Reprocessing invoice ID " + invoice.getId() + " with URL: " + invoice.getPdfUrl());

                // Send both the PDF URL and the originalId (not id)
                Map<String, Object> urlPayload = new HashMap<>();
                urlPayload.put("url", invoice.getPdfUrl());
                urlPayload.put("originalId", invoice.getId()); // ✅ Use originalId

                HttpEntity<Map<String, Object>> request = new HttpEntity<>(urlPayload, headers);

                ResponseEntity<PendingInvoiceDTO> response = restTemplate.postForEntity(
                        FIX_MISMATCHED_URL,
                        request,
                        PendingInvoiceDTO.class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    PendingInvoiceDTO fixedInvoice = response.getBody();

                    // Save the corrected pending invoice
                    // ✅ keep the original SMS timestamp so your cutoff logic stays correct
                    fixedInvoice.setReceivedAtSms(invoice.getReceivedAtSms());

                    // ✅ Save in-process instead of POSTing back to our own HTTP endpoint.
                    pendingInvoiceService.savePendingInvoice(fixedInvoice);
                    System.out.println("✅ Fixed invoice saved (in-process): ID " + invoice.getId());

                } else {
                    System.err.println("❌ Python tool failed for invoice ID " + invoice.getId() + ": " + response.getStatusCode());
                }

            } catch (Exception e) {
                System.err.println("❌ Exception while reprocessing invoice ID " + invoice.getId() + ": " + e.getMessage());
            }
        }
    }


}
