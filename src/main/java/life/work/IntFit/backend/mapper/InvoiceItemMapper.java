package life.work.IntFit.backend.mapper;

import life.work.IntFit.backend.dto.InvoiceItemDTO;
import life.work.IntFit.backend.model.entity.InvoiceItem;
import org.mapstruct.*;

import java.util.List;

//@Mapper(componentModel = "spring")
//public interface InvoiceItemMapper {
//
//    @Mapping(source = "material.id", target = "materialId")
//    @Mapping(source = "material.name", target = "materialName")
//    InvoiceItemDTO toDTO(InvoiceItem item);
//
//    @Mapping(target = "material", ignore = true) // will be manually handled
//    @Mapping(target = "invoice", ignore = true)  // set by parent context
//    InvoiceItem toEntity(InvoiceItemDTO dto);
//
//    List<InvoiceItemDTO> toDTOs(List<InvoiceItem> items);
//    List<InvoiceItem> toEntities(List<InvoiceItemDTO> dtos);
//}

@Mapper(componentModel = "spring")
public interface InvoiceItemMapper {

    @Mapping(source = "material.id", target = "materialId")
    InvoiceItemDTO toDTO(InvoiceItem item);

    @Mapping(target = "material", ignore = true) // you'll manually set material in service
    @Mapping(target = "invoice", ignore = true)  // you'll manually set invoice in service
    InvoiceItem toEntity(InvoiceItemDTO dto);

    List<InvoiceItemDTO> toDTOs(List<InvoiceItem> items);
    List<InvoiceItem> toEntities(List<InvoiceItemDTO> dtos);
}
