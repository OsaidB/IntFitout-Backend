package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.BankCheckDTO;
import life.work.IntFit.backend.model.entity.BankCheck;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import org.mapstruct.*;

@Mapper(
        componentModel = "spring",
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface BankCheckMapper {

    // ---------- Entity -> DTO ----------
    @Mappings({
            @Mapping(target = "sourceMasterWorksiteId",
                    expression = "java(entity.getSourceMasterWorksite() != null ? entity.getSourceMasterWorksite().getId() : null)")
    })
    BankCheckDTO toDto(BankCheck entity);

    // ---------- DTO -> Entity ----------
    @Mappings({
            @Mapping(target = "sourceMasterWorksite",
                    expression = "java(mapMaster(dto.getSourceMasterWorksiteId()))")
    })
    BankCheck toEntity(BankCheckDTO dto);

    // ---------- PATCH-like update ----------
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void update(@MappingTarget BankCheck target, BankCheckDTO patch);

    // ---------- Normalization ----------
    @AfterMapping
    default void normalizeDto(@MappingTarget BankCheckDTO dto) {
        dto.setRecipientName(clean(dto.getRecipientName()));
        dto.setNotes(clean(dto.getNotes()));
        dto.setFromWhom(clean(dto.getFromWhom()));
        dto.setSerialNumber(clean(dto.getSerialNumber()));
        dto.setImageUrl(clean(dto.getImageUrl()));
        dto.setSourceMasterWorksiteName(clean(dto.getSourceMasterWorksiteName()));
        // dto.personalIssuer intentionally not touched (Boolean for PATCH)
    }

    @AfterMapping
    default void normalizeEntity(@MappingTarget BankCheck target) {
        target.setRecipientName(clean(target.getRecipientName()));
        target.setNotes(clean(target.getNotes()));
        target.setFromWhom(clean(target.getFromWhom()));
        target.setSerialNumber(clean(target.getSerialNumber()));
        target.setImageUrl(clean(target.getImageUrl()));
        target.setSourceMasterWorksiteName(clean(target.getSourceMasterWorksiteName()));

        // Back-compat: infer personalIssuer from fromWhom == "personal" if not already flagged
        if (!target.isPersonalIssuer() && target.getFromWhom() != null) {
            if ("personal".equalsIgnoreCase(target.getFromWhom().trim())) {
                target.setPersonalIssuer(true);
            }
        }
    }

    // Make PATCH of relation & boolean safe: only apply when present
    @AfterMapping
    default void applyPatchSpecials(@MappingTarget BankCheck target, BankCheckDTO src) {
        // Only set relation if ID provided
        if (src.getSourceMasterWorksiteId() != null) {
            target.setSourceMasterWorksite(mapMaster(src.getSourceMasterWorksiteId()));
        }
        // Only set cached name if provided
        if (src.getSourceMasterWorksiteName() != null) {
            target.setSourceMasterWorksiteName(clean(src.getSourceMasterWorksiteName()));
        }
        // Only set personalIssuer if provided (Boolean)
        if (src.getPersonalIssuer() != null) {
            target.setPersonalIssuer(Boolean.TRUE.equals(src.getPersonalIssuer()));
        }
    }

    // ---------- helpers ----------
    default MasterWorksite mapMaster(Long id) {
        if (id == null) return null;
        MasterWorksite mw = new MasterWorksite();
        mw.setId(id); // JPA will treat this as a reference
        return mw;
    }

    default String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
