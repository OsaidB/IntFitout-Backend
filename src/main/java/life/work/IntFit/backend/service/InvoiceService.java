package life.work.IntFit.backend.service;

import life.work.IntFit.backend.controller.criteria.InvoiceSearchCriteria;
import life.work.IntFit.backend.controller.criteria.InvoiceSpecs;
import life.work.IntFit.backend.dto.InvoiceDTO;
import life.work.IntFit.backend.mapper.InvoiceMapper;
import life.work.IntFit.backend.model.entity.*;
import life.work.IntFit.backend.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import life.work.IntFit.backend.utils.NameCleaner;


@Service
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final WorksiteRepository worksiteRepository;
    private final MaterialRepository materialRepository;
    private final InvoiceMapper invoiceMapper;

    public InvoiceService(InvoiceRepository invoiceRepository,
                          WorksiteRepository worksiteRepository,
                          MaterialRepository materialRepository,
                          InvoiceMapper invoiceMapper) {
        this.invoiceRepository = invoiceRepository;
        this.worksiteRepository = worksiteRepository;
        this.materialRepository = materialRepository;
        this.invoiceMapper = invoiceMapper;
    }

    @Transactional
    public InvoiceDTO saveInvoice(InvoiceDTO dto) {
        Worksite worksite;

        if (dto.getWorksiteId() != null) {
            worksite = worksiteRepository.findById(dto.getWorksiteId())
                    .orElseThrow(() -> new IllegalArgumentException("Worksite not found"));
        } else {
            String worksiteName = normalizeWorksiteName(dto.getWorksiteName());
            worksite = worksiteRepository.findByName(worksiteName)
                    .orElseGet(() -> worksiteRepository.save(
                            Worksite.builder().name(worksiteName).build()
                    ));
        }

        // Build invoice manually (date is LocalDateTime – keep it as-is)
        Invoice invoice = Invoice.builder()
                .date(dto.getDate())
                .netTotal(dto.getNetTotal())
                .total(dto.getTotal())
                .total_match(dto.getTotal_match())
                .pdfUrl(dto.getPdfUrl())
                .parsedAt(dto.getParsedAt())
                .reprocessedFromId(dto.getReprocessedFromId())
                .worksite(worksite)
                .worksiteName(dto.getWorksiteName())
                .build();

        // Process items and materials
        List<InvoiceItem> items = dto.getItems().stream().map(itemDTO -> {
            String rawName = Optional.ofNullable(itemDTO.getDescription())
                    .orElseThrow(() -> new IllegalArgumentException("Material description is missing"));

            // Normalize the name using NameCleaner
            String cleanedName = NameCleaner.clean(rawName);

            // Lookup or create the material using the cleaned name
            Material material = materialRepository.findByName(cleanedName)
                    .orElseGet(() -> materialRepository.save(Material.builder().name(cleanedName).build()));


            return InvoiceItem.builder()
                    .description(cleanedName) // Store cleaned name as description
                    .quantity(itemDTO.getQuantity())
                    .unit_price(itemDTO.getUnit_price())
                    .total_price(itemDTO.getTotal_price())
                    .material(material)
                    .invoice(invoice)
                    .build();
        }).toList();

        invoice.setItems(items);
        Invoice saved = invoiceRepository.save(invoice);

        return invoiceMapper.toDTO(saved);
    }

    public List<InvoiceDTO> getInvoicesByWorksiteId(Long worksiteId) {
        List<Invoice> invoices = invoiceRepository.findByWorksiteId(worksiteId);
        return invoiceMapper.toDTOs(invoices);
    }

    public Optional<InvoiceDTO> getInvoiceById(Long id) {
        return invoiceRepository.findById(id).map(invoiceMapper::toDTO);
    }

    public List<InvoiceDTO> getLast20Invoices() {
        Pageable limit = PageRequest.of(0, 20);
        List<Invoice> invoices = invoiceRepository.findRecentInvoices(limit);
        return invoiceMapper.toDTOs(invoices);
    }

    /**
     * Latest actual business datetime (keeps time; used by /latest-business-datetime).
     */
    public Optional<LocalDateTime> getLastSavedInvoiceDateTime() {
        return invoiceRepository.findTopByOrderByDateDesc()
                .map(Invoice::getDate);
    }

    /**
     * Deprecated wrapper kept for compatibility – trims to LocalDate.
     * Prefer getLastSavedInvoiceDateTime().
     */
    @Deprecated
    public Optional<LocalDate> getLastSavedInvoiceDate() {
        return getLastSavedInvoiceDateTime().map(LocalDateTime::toLocalDate);
    }

    /**
     * Get all invoices that occurred on a specific business day (YYYY-MM-DD),
     * using a day window [day 00:00, next day 00:00).
     */
    public List<InvoiceDTO> getInvoicesByDate(String date) {
        LocalDate day = LocalDate.parse(date);
        LocalDateTime start = day.atStartOfDay();
        LocalDateTime end = day.plusDays(1).atStartOfDay();
        List<Invoice> invoices = invoiceRepository.findByDateBetween(start, end);
        return invoiceMapper.toDTOs(invoices);
    }

    public static String normalizeWorksiteName(String input) {
        if (input == null) return null;

        // Collapse multiple spaces into one, trim, lowercase
        return input.replaceAll("\\s+", " ").trim().toLowerCase();
    }


    public Page<InvoiceDTO> search(InvoiceSearchCriteria c, Pageable pageable) {
        Specification<Invoice> spec = Specification.where(null);

        // date range
        LocalDateTime from = parseLdt(c.getFromDate());
        LocalDateTime to   = parseLdt(c.getToDate());
        if (from != null) spec = spec.and(InvoiceSpecs.dateGte(from));
        if (to != null)   spec = spec.and(InvoiceSpecs.dateLt(to.plusDays(1))); // inclusive end-of-day

        // worksite
        if (c.getWorksiteId() != null) {
            spec = spec.and(InvoiceSpecs.worksiteIdEq(c.getWorksiteId()));
        }

        // master worksite (join)
        if (c.getMasterWorksiteId() != null) {
            spec = spec.and(InvoiceSpecs.masterWorksiteIdEq(c.getMasterWorksiteId()));
        }

        // free-text q (id/worksiteName/pdfUrl)
        if (c.getQ() != null && !c.getQ().isBlank()) {
            spec = spec.and(InvoiceSpecs.freeText(c.getQ()));
        }

        Page<Invoice> page = invoiceRepository.findAll(spec, pageable);
        return page.map(invoiceMapper::toDto);
    }

    private LocalDateTime parseLdt(String s) {
        if (s == null || s.isBlank()) return null;
        try { return LocalDateTime.parse(s); } catch (DateTimeParseException e) { return null; }
    }

}
