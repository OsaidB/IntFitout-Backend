package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.BankCheckDTO;
import life.work.IntFit.backend.model.entity.BankCheck;
import org.mapstruct.*;


/**
 * MapStruct mapper:
 * - Maps by field name (includes imageUrl)
 * - AfterMapping normalizes strings (trim, blank -> null)
 * - update(...) supports PATCH semantics (ignores nulls)
 */
@Mapper(
        componentModel = "spring",
        nullValueCheckStrategy = NullValueCheckStrategy.ALWAYS
)
public interface BankCheckMapper {

    // Entity -> DTO
    BankCheckDTO toDto(BankCheck entity);

    // DTO -> Entity
    BankCheck toEntity(BankCheckDTO dto);

    // PATCH-like update: only non-null values from DTO will update the target entity
    @BeanMapping(nullValuePropertyMappingStrategy = NullValuePropertyMappingStrategy.IGNORE)
    void update(@MappingTarget BankCheck target, BankCheckDTO patch);

    // --- Normalize strings on the way out/in ---------------------------------

    @AfterMapping
    default void normalizeDto(@MappingTarget BankCheckDTO dto) {
        dto.setRecipientName(clean(dto.getRecipientName()));
        dto.setNotes(clean(dto.getNotes()));
        dto.setFromWhom(clean(dto.getFromWhom()));
        dto.setSerialNumber(clean(dto.getSerialNumber()));
        dto.setImageUrl(clean(dto.getImageUrl()));
    }

    @AfterMapping
    default void normalizeEntity(@MappingTarget BankCheck target) {
        target.setRecipientName(clean(target.getRecipientName()));
        target.setNotes(clean(target.getNotes()));
        target.setFromWhom(clean(target.getFromWhom()));
        target.setSerialNumber(clean(target.getSerialNumber()));
        target.setImageUrl(clean(target.getImageUrl()));
    }

    // helper
    default String clean(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
