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

                // ✅ Step 2: Wrap the parsed invoice in a List and send to Spring Boot backend
                List<PendingInvoiceDTO> wrappedList = List.of(parsedInvoice);
                HttpEntity<List<PendingInvoiceDTO>> saveRequest = new HttpEntity<>(wrappedList, headers);

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
}
