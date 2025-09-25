package life.work.IntFit.backend.dto;

import java.math.BigDecimal;
import java.util.List;

public class AllocateRequestDTO {

    public List<Line> allocations;

    public static class Line {
        public Long invoiceId;
        public BigDecimal amount;
    }
}
