package life.work.IntFit.backend.dto.billing.AR;

import java.math.BigDecimal;

public class UpdateChargeDTO {
    public String dateISO;      // optional
    public String description;  // optional
    public BigDecimal amount;   // optional
}