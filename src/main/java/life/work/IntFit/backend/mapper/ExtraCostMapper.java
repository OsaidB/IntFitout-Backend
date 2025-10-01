// src/main/java/life/work/IntFit/backend/mapper/ExtraCostMapper.java
package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.ExtraCostDTO;
import life.work.IntFit.backend.model.entity.ExtraCost;

public final class ExtraCostMapper {

    private ExtraCostMapper() { /* utils */ }

    public static ExtraCostDTO toDTO(ExtraCost e) {
        if (e == null) return null;
        return new ExtraCostDTO(
                e.getId(),
                e.getMasterWorksiteId(),
                e.getWorksiteId(),
                e.getCostDate(),                // LocalDate (nullable => general)
                e.getAmount(),
                e.getDescription(),
                e.isGeneral()                   // boolean getter
        );
    }

    public static void apply(ExtraCost e, ExtraCostDTO d) {
        if (e == null || d == null) return;

        e.setMasterWorksiteId(d.masterWorksiteId());
        e.setWorksiteId(d.worksiteId());
        e.setCostDate(d.date()); // null allowed => general
        e.setAmount(d.amount());
        e.setDescription(d.description() == null ? "" : d.description().trim());

        // If date is null, force general=true (keeps DB + entity consistent)
        boolean general = Boolean.TRUE.equals(d.isGeneral()) || d.date() == null;
        e.setGeneral(general);
    }
}
