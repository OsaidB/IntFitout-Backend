package life.work.IntFit.backend.utils;

import life.work.IntFit.backend.dto.PendingInvoiceDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class PythonInvoiceProcessor {

    private final RestTemplate restTemplate = new RestTemplate();
    private static final String PYTHON_API_URL = "https://invoices-convertor-1.onrender.com/process-invoice";

    // 🔁 Internal endpoint to save the pending invoice
    private static final String SAVE_PENDING_ENDPOINT = "https://intfitout-backend-production.up.railway.app/api/invoices/pending/upload";

    public void sendInvoiceToPython(String invoiceUrl) {
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

                HttpEntity<PendingInvoiceDTO> saveRequest = new HttpEntity<>(parsedInvoice, headers);

                ResponseEntity<Void> saveResponse = restTemplate.postForEntity(
                        SAVE_PENDING_ENDPOINT,
                        saveRequest,
                        Void.class
                );

                if (saveResponse.getStatusCode().is2xxSuccessful()) {
                    System.out.println("✅ Pending invoice saved successfully");
                } else {
                    System.err.println("❌ Failed to save pending invoice: " + saveResponse.getStatusCode());
                }

            } else {
                System.err.println("❌ Python tool returned non-success status: " + response.getStatusCode());
            }

        } catch (Exception e) {
            System.err.println("❌ Exception during Python invoice processing: " + e.getMessage());
        }
    }

    public void reprocessMismatchedInvoices(List<String> invoiceUrls) {
        String FIX_MISMATCHED_URL = "https://invoices-convertor-1.onrender.com/fix-mismatched";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        for (String url : invoiceUrls) {
            try {
                System.out.println("🔁 Sending to fix-mismatched: " + url);

                // Send invoice URL to fix-mismatched endpoint
                Map<String, String> urlPayload = new HashMap<>();
                urlPayload.put("url", url);
                HttpEntity<Map<String, String>> request = new HttpEntity<>(urlPayload, headers);

                ResponseEntity<PendingInvoiceDTO> response = restTemplate.postForEntity(
                        FIX_MISMATCHED_URL,
                        request,
                        PendingInvoiceDTO.class
                );

                if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                    PendingInvoiceDTO fixedInvoice = response.getBody();

                    // Save the corrected pending invoice
                    List<PendingInvoiceDTO> wrapped = List.of(fixedInvoice);
                    HttpEntity<List<PendingInvoiceDTO>> saveRequest = new HttpEntity<>(wrapped, headers);

                    ResponseEntity<Void> saveResponse = restTemplate.postForEntity(
                            SAVE_PENDING_ENDPOINT,
                            saveRequest,
                            Void.class
                    );

                    if (saveResponse.getStatusCode().is2xxSuccessful()) {
                        System.out.println("✅ Fixed invoice saved: " + url);
                    } else {
                        System.err.println("❌ Failed to save fixed invoice: " + saveResponse.getStatusCode());
                    }

                } else {
                    System.err.println("❌ Failed to fix invoice: " + response.getStatusCode());
                }

            } catch (Exception e) {
                System.err.println("❌ Exception while fixing invoice: " + url + " → " + e.getMessage());
            }
        }
    }


}
