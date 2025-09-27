package life.work.IntFit.backend.dto.billing.AR;

import java.math.BigDecimal;

public class StatementChargeDTO {
    public Long id;
    public String dateISO;
    public String description;
    public BigDecimal amount = BigDecimal.ZERO;
    public BigDecimal paid = BigDecimal.ZERO;
    public BigDecimal remaining = BigDecimal.ZERO;
}
