package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.PendingInvoiceDTO;
import life.work.IntFit.backend.model.entity.PendingInvoice;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", uses = PendingInvoiceItemMapper.class)
public interface PendingInvoiceMapper {

    @Mapping(source = "worksite.id", target = "worksiteId")
    PendingInvoiceDTO toDTO(PendingInvoice pendingInvoice);

    @Mapping(target = "worksite", ignore = true) // set manually on confirmation
    PendingInvoice toEntity(PendingInvoiceDTO dto);

    List<PendingInvoiceDTO> toDTOs(List<PendingInvoice> pendingInvoices);
    List<PendingInvoice> toEntities(List<PendingInvoiceDTO> dtos);
}
