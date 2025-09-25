package life.work.IntFit.backend.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class StatementDTO {
    public BigDecimal openingBalance = BigDecimal.ZERO;
    public BigDecimal adjustments = BigDecimal.ZERO; // future: credit notes etc.

    public List<StatementInvoiceDTO> invoices = new ArrayList<>();
    public List<StatementPaymentDTO> payments = new ArrayList<>();
}
