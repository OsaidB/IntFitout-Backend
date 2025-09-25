// File: dto/ArPaymentDTO.java
package life.work.IntFit.backend.dto;

import java.math.BigDecimal;

public class ArPaymentDTO {
    public Long id;
    public Long masterWorksiteId;
    public String dateISO;     // "YYYY-MM-DD"
    public BigDecimal amount;
    public String method;
    public String reference;
    public String notes;
    public String createdAt;   // ISO-8601
}
