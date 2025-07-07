package life.work.IntFit.backend.utils;

import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Component
public class PythonInvoiceProcessor {

    private final RestTemplate restTemplate = new RestTemplate();

    // ✅ Deployed Python API URL
    private static final String PYTHON_API_URL = "https://invoices-convertor-1.onrender.com/process-invoice";

    // 🔒 Optional secret key – if you later enable header-based auth
    private static final String SECRET_KEY = ""; // Leave empty for now

    public void sendInvoiceToPython(String invoiceUrl) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        // Optional: if you decide to secure your endpoint later
        if (!SECRET_KEY.isEmpty()) {
            headers.set("x-api-key", SECRET_KEY);
        }

        Map<String, String> body = new HashMap<>();
        body.put("url", invoiceUrl);

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(PYTHON_API_URL, request, String.class);
            System.out.println("✅ Python response: " + response.getBody());
        } catch (Exception e) {
            System.err.println("❌ Failed to call Python service: " + e.getMessage());
        }
    }
}
