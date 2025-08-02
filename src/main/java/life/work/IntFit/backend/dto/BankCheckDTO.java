package life.work.IntFit.backend.dto;

import lombok.Data;

import java.time.LocalDate;

@Data
public class BankCheckDTO {
    private Long id;
    private double amount;
    private LocalDate dueDate;
    private String recipientName;
    private String notes;
    private boolean cleared;
}
