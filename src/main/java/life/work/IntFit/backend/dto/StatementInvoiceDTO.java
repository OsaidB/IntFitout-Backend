package life.work.IntFit.backend.dto;

import java.math.BigDecimal;

public class StatementInvoiceDTO {
    public Long id;
    public String number;
    public String dateISO;
    public BigDecimal total = BigDecimal.ZERO;
    public BigDecimal paid = BigDecimal.ZERO;
    public BigDecimal remaining = BigDecimal.ZERO;
}
