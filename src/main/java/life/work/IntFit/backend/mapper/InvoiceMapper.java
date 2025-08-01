package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.InvoiceDTO;
import life.work.IntFit.backend.model.entity.Invoice;
import org.mapstruct.*;

import java.util.List;

@Mapper(componentModel = "spring", uses = InvoiceItemMapper.class)
public interface InvoiceMapper {

    @Mapping(source = "worksite.id", target = "worksiteId")
    @Mapping(source = "worksite.name", target = "worksiteName")
    @Mapping(source = "pdfUrl", target = "pdfUrl")
    @Mapping(source = "parsedAt", target = "parsedAt")
    @Mapping(source = "reprocessedFromId", target = "reprocessedFromId")
    InvoiceDTO toDTO(Invoice invoice);

    @Mapping(target = "worksite", ignore = true) // set manually
    @Mapping(source = "pdfUrl", target = "pdfUrl")
    @Mapping(source = "parsedAt", target = "parsedAt")
    @Mapping(source = "reprocessedFromId", target = "reprocessedFromId")
    Invoice toEntity(InvoiceDTO dto);

    List<InvoiceDTO> toDTOs(List<Invoice> invoices);
    List<Invoice> toEntities(List<InvoiceDTO> dtos);
}
