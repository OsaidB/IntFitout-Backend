package life.work.IntFit.backend.dto.billing.AR;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class StatementDTO {
    public BigDecimal openingBalance = BigDecimal.ZERO;
    public BigDecimal adjustments = BigDecimal.ZERO; // future

    public List<StatementChargeDTO> charges = new ArrayList<>();
    public List<StatementPaymentDTO> payments = new ArrayList<>();
}
