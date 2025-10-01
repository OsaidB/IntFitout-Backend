// src/main/java/life/work/IntFit/backend/dto/ExtraCostDTO.java
package life.work.IntFit.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ExtraCostDTO(
        Long id,
        Long masterWorksiteId,
        Long worksiteId,
        LocalDate date,           // null => general
        BigDecimal amount,
        String description,
        Boolean isGeneral
) {}
