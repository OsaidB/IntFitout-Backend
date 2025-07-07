package life.work.IntFit.backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SmsMessageDTO {
    private String type; // "invoice" or "status"
    private String content;
    private String receivedAt; // ISO datetime as string
}
