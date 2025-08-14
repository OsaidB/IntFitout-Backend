package life.work.IntFit.backend.model.entity;

import jakarta.persistence.*;
import life.work.IntFit.backend.model.enums.StatusType;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class StatusMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String content;

    private LocalDateTime receivedAt;

    private Double amount;

    private Double totalOwed;

    @Enumerated(EnumType.STRING)
    private StatusType statusType;  // ✅ NEW

    private LocalDate balanceDate;  // ✅ For "رصيدكم لغاية"
}
