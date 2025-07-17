package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.InvoiceDTO;
import life.work.IntFit.backend.dto.InvoiceItemDTO;
import life.work.IntFit.backend.mapper.InvoiceItemMapper;
import life.work.IntFit.backend.mapper.InvoiceMapper;
import life.work.IntFit.backend.model.entity.*;
import life.work.IntFit.backend.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import life.work.IntFit.backend.utils.PythonInvoiceProcessor;


@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceItemRepository itemRepository;
    private final WorksiteRepository worksiteRepository;
    private final MaterialRepository materialRepository;
    private final InvoiceMapper invoiceMapper;
    private final InvoiceItemMapper itemMapper;

    @Autowired
    private PythonInvoiceProcessor pythonProcessor;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          InvoiceItemRepository itemRepository,
                          WorksiteRepository worksiteRepository,
                          MaterialRepository materialRepository,
                          InvoiceMapper invoiceMapper,
                          InvoiceItemMapper itemMapper) {
        this.invoiceRepository = invoiceRepository;
        this.itemRepository = itemRepository;
        this.worksiteRepository = worksiteRepository;
        this.materialRepository = materialRepository;
        this.invoiceMapper = invoiceMapper;
        this.itemMapper = itemMapper;
    }

    @Transactional
    public InvoiceDTO saveInvoice(InvoiceDTO dto) {
        Invoice invoice = invoiceMapper.toEntity(dto);

        Worksite worksite;

        if (dto.getWorksiteId() != null) {
            worksite = worksiteRepository.findById(dto.getWorksiteId())
                    .orElseThrow(() -> new IllegalArgumentException("Worksite not found"));
        } else {
            String worksiteName = dto.getWorksiteName().trim();
            worksite = worksiteRepository.findByNameIgnoreCase(worksiteName)
                    .orElseGet(() -> worksiteRepository.save(
                            Worksite.builder().name(worksiteName).build()
                    ));
        }

        invoice.setWorksite(worksite);

        List<InvoiceItem> items = dto.getItems().stream().map(itemDTO -> {
            InvoiceItem item = itemMapper.toEntity(itemDTO);
            item.setInvoice(invoice);

            String materialName = Optional.ofNullable(itemDTO.getDescription())
                    .orElseThrow(() -> new IllegalArgumentException("Material description is missing"));

            Material material = materialRepository.findByNameIgnoreCase(materialName)
                    .orElseGet(() -> materialRepository.save(
                            Material.builder()
                                    .name(materialName)
                                    // .unit(itemDTO.getUnit())
                                    .build()
                    ));

            item.setMaterial(material);
            return item;
        }).toList();

        invoice.setItems(items);
        Invoice saved = invoiceRepository.save(invoice);

        // 🔁 Send to Python processor
        String invoiceUrl = "https://your-railway-app-url/api/invoices/" + saved.getId();
        pythonProcessor.sendInvoiceToPython(invoiceUrl);

        return invoiceMapper.toDTO(saved);
    }

    public Optional<InvoiceDTO> getInvoice(Long id) {
        return invoiceRepository.findById(id).map(invoiceMapper::toDTO);
    }

    public List<InvoiceDTO> getInvoicesByWorksiteId(Long worksiteId) {
        List<Invoice> invoices = invoiceRepository.findByWorksiteId(worksiteId);
        return invoiceMapper.toDTOs(invoices);
    }

    public void deleteInvoice(Long id) {
        invoiceRepository.deleteById(id);
    }

    public Optional<InvoiceDTO> getInvoiceById(Long id) {
        return invoiceRepository.findById(id).map(invoiceMapper::toDTO);
    }

    public List<InvoiceDTO> getLast20Invoices() {
        Pageable limit = PageRequest.of(0, 20);
        List<Invoice> invoices = invoiceRepository.findRecentInvoices(limit);
        return invoiceMapper.toDTOs(invoices);
    }

    public Optional<LocalDate> getLastSavedInvoiceDate() {
        return invoiceRepository.findTopByOrderByDateDesc()
                .map(i -> i.getDate().toLocalDate());
    }


}
