package life.work.IntFit.backend.dto.billing.AR;

import java.math.BigDecimal;

public class StatementPaymentAllocationDTO {
    public Long invoiceId;
    public BigDecimal amount = BigDecimal.ZERO;
}
