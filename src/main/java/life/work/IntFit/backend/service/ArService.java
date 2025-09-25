package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.AllocateRequestDTO;
import life.work.IntFit.backend.dto.StatementDTO;
import life.work.IntFit.backend.dto.StatementInvoiceDTO;

import java.time.LocalDate;
import java.util.List;

public interface ArService {
    StatementDTO getStatement(Long masterWorksiteId, LocalDate from, LocalDate to);

    // ⬇️ Change return type from Object to List<StatementInvoiceDTO>
    List<StatementInvoiceDTO> getOpenInvoices(Long masterWorksiteId, LocalDate asOf);

    void allocatePayment(Long paymentId, AllocateRequestDTO body);
}
