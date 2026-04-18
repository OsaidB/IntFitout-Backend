package life.work.IntFit.backend.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AreaDTO {
    private Long id;
    private String name;
    private Long cityId;
    private String cityName;
}