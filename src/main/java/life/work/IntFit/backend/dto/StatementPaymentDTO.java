package life.work.IntFit.backend.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class StatementPaymentDTO {
    public Long id;
    public String dateISO;
    public BigDecimal amount = BigDecimal.ZERO;
    public String method;
    public String reference;
    public String notes;

    public List<StatementPaymentAllocationDTO> allocations = new ArrayList<>();
}
