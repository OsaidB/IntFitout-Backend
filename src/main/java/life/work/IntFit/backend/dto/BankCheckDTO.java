package life.work.IntFit.backend.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;

@Data
public class BankCheckDTO {
    private Long id;
    private BigDecimal amount;
    private LocalDate dueDate;
    private String recipientName;
    private String notes;
    private boolean cleared;

    /** Issuer (exactly as written on the check). */
    private String fromWhom;

    private String serialNumber;

    private String imageUrl;

    /** NEW — personal issuer flag.
     *  NOTE: Use Boolean for PATCH semantics (null = don't change). */
    private Boolean personalIssuer;

    /** NEW — “Taken from” Master Worksite (id + cached name). */
    private Long   sourceMasterWorksiteId;
    private String sourceMasterWorksiteName;
}
