package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.PendingInvoiceItemDTO;
import life.work.IntFit.backend.model.entity.PendingInvoiceItem;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface PendingInvoiceItemMapper {

    @Mapping(source = "material.id", target = "materialId")
    PendingInvoiceItemDTO toDTO(PendingInvoiceItem entity);

    @Mapping(target = "pendingInvoice", ignore = true)
    PendingInvoiceItem toEntity(PendingInvoiceItemDTO dto);
}
