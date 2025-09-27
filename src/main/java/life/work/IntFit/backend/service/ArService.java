// File: life/work/IntFit/backend/service/ArService.java
package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.billing.AR.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public interface ArService {

    // -------- Statement & Open (legacy "invoices" shape, now representing charges) --------
    StatementDTO getStatement(Long masterWorksiteId, LocalDate from, LocalDate to);

    // Returns open charges using your existing StatementInvoiceDTO shape for compatibility
    List<StatementInvoiceDTO> getOpenInvoices(Long masterWorksiteId, LocalDate asOf);

    // -------- Payments --------
    void allocatePayment(Long paymentId, AllocateRequestDTO body);

    ArPaymentDTO createPayment(CreatePaymentDTO body);

    List<ArPaymentDTO> listPayments(LocalDate from, LocalDate to, Long masterWorksiteId);

    // -------- Charges (CRUD) --------
    // Create a new charge for a worksite
    StatementChargeDTO createCharge(Long masterWorksiteId, String dateISO, String description, BigDecimal amount);

    // Update an existing charge (any field nullable -> ignore if null)
    void updateCharge(Long chargeId, String dateISO, String description, BigDecimal amount);

    // Hard delete a charge (should only be allowed if no allocations)
    void deleteCharge(Long chargeId);
}
