// File: dto/CreatePaymentDTO.java
package life.work.IntFit.backend.dto;

import java.math.BigDecimal;

public class CreatePaymentDTO {
    public Long masterWorksiteId;
    public String dateISO;     // "YYYY-MM-DD"
    public BigDecimal amount;
    public String method;
    public String reference;   // we'll send "MOB:<localId>" for dedupe
    public String notes;
}
