package life.work.IntFit.backend.dto.billing.AR;

import java.math.BigDecimal;

public class CreateChargeDTO {
    public Long masterWorksiteId;
    public String dateISO;      // "YYYY-MM-DD"
    public String description;  // optional
    public BigDecimal amount;
}