package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.InvoiceDTO;
import life.work.IntFit.backend.service.InvoiceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/invoices")
@CrossOrigin("*")
public class InvoiceController {

    private final InvoiceService invoiceService;

    public InvoiceController(InvoiceService invoiceService) {
        this.invoiceService = invoiceService;
    }

    @PostMapping
    public ResponseEntity<InvoiceDTO> createInvoice(@RequestBody InvoiceDTO invoiceDTO) {
        InvoiceDTO saved = invoiceService.saveInvoice(invoiceDTO);
        return ResponseEntity.ok(saved);
    }

    @GetMapping("/by-worksite/{worksiteId}")
    public ResponseEntity<List<InvoiceDTO>> getInvoicesByWorksite(@PathVariable Long worksiteId) {
        return ResponseEntity.ok(invoiceService.getInvoicesByWorksiteId(worksiteId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<InvoiceDTO> getInvoiceById(@PathVariable Long id) {
        return invoiceService.getInvoiceById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }
}
