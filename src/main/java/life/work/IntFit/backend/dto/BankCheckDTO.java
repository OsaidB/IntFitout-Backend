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

    private String fromWhom;      // NEW
    private String serialNumber;  // NEW

    // ⬇️ NEW
    private String imageUrl;

}
