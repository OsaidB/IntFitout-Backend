package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.InvoiceDTO;
import life.work.IntFit.backend.dto.PendingInvoiceDTO;
import life.work.IntFit.backend.dto.PendingInvoiceItemDTO;
import life.work.IntFit.backend.dto.SmsMessageDTO;
import life.work.IntFit.backend.mapper.InvoiceMapper;
import life.work.IntFit.backend.mapper.PendingInvoiceItemMapper;
import life.work.IntFit.backend.mapper.PendingInvoiceMapper;
import life.work.IntFit.backend.model.entity.*;
import life.work.IntFit.backend.repository.*;
import life.work.IntFit.backend.utils.PythonInvoiceProcessor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

@Service
public class PendingInvoiceService {

    private final PendingInvoiceRepository pendingInvoiceRepository;
    private final PendingInvoiceItemRepository itemRepository;
    private final WorksiteRepository worksiteRepository;
    private final MaterialRepository materialRepository;
    private final PendingInvoiceMapper pendingInvoiceMapper;
    private final PendingInvoiceItemMapper itemMapper;

    private final InvoiceRepository invoiceRepository;
    private final InvoiceMapper invoiceMapper;


    private final PythonInvoiceProcessor pythonInvoiceProcessor;
    private final StatusMessageRepository statusMessageRepository;

    public PendingInvoiceService(
            PendingInvoiceRepository pendingInvoiceRepository,
            PendingInvoiceItemRepository itemRepository,
            WorksiteRepository worksiteRepository,
            MaterialRepository materialRepository,
            InvoiceRepository invoiceRepository,
            InvoiceMapper invoiceMapper,
            PendingInvoiceMapper pendingInvoiceMapper,
            PendingInvoiceItemMapper itemMapper,
            PythonInvoiceProcessor pythonInvoiceProcessor,               // ✅ ADD THIS
            StatusMessageRepository statusMessageRepository              // ✅ AND THIS
    ) {
        this.pendingInvoiceRepository = pendingInvoiceRepository;
        this.itemRepository = itemRepository;
        this.worksiteRepository = worksiteRepository;
        this.materialRepository = materialRepository;
        this.invoiceRepository = invoiceRepository;
        this.invoiceMapper = invoiceMapper;
        this.pendingInvoiceMapper = pendingInvoiceMapper;
        this.itemMapper = itemMapper;
        this.pythonInvoiceProcessor = pythonInvoiceProcessor;       // ✅
        this.statusMessageRepository = statusMessageRepository;     // ✅
    }



    @Transactional
    public PendingInvoiceDTO savePendingInvoice(PendingInvoiceDTO dto) {
        // Convert DTO to entity
        PendingInvoice pendingInvoice = pendingInvoiceMapper.toEntity(dto);
        pendingInvoice.setParsedAt(LocalDateTime.now());
        pendingInvoice.setConfirmed(false);

        // ✅ Set reprocessedFromId directly (NO object reference!)
        pendingInvoice.setReprocessedFromId(dto.getReprocessedFromId());

        // Map and prepare invoice items
        List<PendingInvoiceItem> items = dto.getItems().stream().map(itemDTO -> {
            PendingInvoiceItem item = itemMapper.toEntity(itemDTO);
            item.setPendingInvoice(pendingInvoice);

            // Resolve or create material
            String materialName = Optional.ofNullable(itemDTO.getDescription())
                    .orElseThrow(() -> new IllegalArgumentException("Material description is missing"));

            Material material = materialRepository.findByNameIgnoreCase(materialName)
                    .orElseGet(() -> materialRepository.save(
                            Material.builder().name(materialName).build()
                    ));

            item.setMaterial(material);
            return item;
        }).toList();

        // Set items and persist
        pendingInvoice.setItems(new HashSet<>(items));

        PendingInvoice saved = pendingInvoiceRepository.save(pendingInvoice);
        return pendingInvoiceMapper.toDTO(saved);
    }

    public Optional<PendingInvoiceDTO> getPendingInvoice(Long id) {
        return pendingInvoiceRepository.findById(id).map(pendingInvoiceMapper::toDTO);
    }

    public List<PendingInvoiceDTO> getAllPendingInvoices() {
        List<PendingInvoice> pendingInvoices = pendingInvoiceRepository.findAllWithItems(); // Uses eager fetch
        return pendingInvoiceMapper.toDTOs(pendingInvoices);
    }


    @Transactional
    public void deletePendingInvoice(Long id) {
        pendingInvoiceRepository.deleteById(id);
    }


    @Transactional
    public void savePendingInvoices(List<PendingInvoiceDTO> pendingInvoices) {
        for (PendingInvoiceDTO dto : pendingInvoices) {
            savePendingInvoice(dto); // reuse your existing method
        }
    }


    @Transactional
    public InvoiceDTO confirmPendingInvoice(Long pendingInvoiceId) {
        PendingInvoice pending = pendingInvoiceRepository.findById(pendingInvoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Pending invoice not found"));

        if (pending.getConfirmed()) {
            throw new IllegalStateException("This invoice has already been confirmed.");
        }

        // Convert to Invoice entity
        Invoice invoice = Invoice.builder()
                .date(pending.getDate())
                .netTotal(pending.getNetTotal())
                .total(pending.getTotal())
                .worksite(pending.getWorksite())
                .worksiteName(pending.getWorksiteName())
                .total_match(pending.getTotalMatch())
                .build();

        List<InvoiceItem> items = pending.getItems().stream().map(pendingItem -> {
            InvoiceItem item = InvoiceItem.builder()
                    .description(pendingItem.getDescription())
                    .quantity(pendingItem.getQuantity())
                    .unit_price(pendingItem.getUnit_price())
                    .total_price(pendingItem.getTotal_price())
                    .material(pendingItem.getMaterial())
                    .invoice(invoice)
                    .build();
            return item;
        }).toList();

        invoice.setItems(items);
        Invoice saved = invoiceRepository.save(invoice);

        // Mark pending invoice as confirmed
        pending.setConfirmed(true);
        pendingInvoiceRepository.save(pending);

        return invoiceMapper.toDTO(saved);
    }


    public void processSmsMessages(List<SmsMessageDTO> messages) {
        for (SmsMessageDTO message : messages) {
            if ("invoice".equalsIgnoreCase(message.getType())) { //type is invoice
                pythonInvoiceProcessor.sendInvoiceToPython(message.getContent());
//                if (parsed != null) {
//                    savePendingInvoice(parsed); // ✅ Actually persist it
//                } else {
//                    System.err.println("❌ Failed to parse invoice from Python tool");
//                }
            } else if ("status".equalsIgnoreCase(message.getType())) {
                String content = message.getContent();
                Double amount = extractArabicNumber(content, "بمبلغ");
                Double newTotalOwed = extractArabicNumber(content, "رصيدكم");

                StatusMessage status = StatusMessage.builder()
                        .content(content)
                        .receivedAt(LocalDateTime.parse(message.getReceivedAt()))
                        .amount(amount)
                        .totalOwed(newTotalOwed)
                        .build();

                statusMessageRepository.save(status);
            }

        }
    }

    private Double extractArabicNumber(String text, String marker) {
        try {
            int markerIndex = text.indexOf(marker);
            if (markerIndex == -1) return null;

            String sub = text.substring(markerIndex + marker.length()).trim();

            // Remove currency symbol and any non-digit/decimal characters
            String number = sub.replaceAll("[^\\d.]", ""); // keeps digits and '.'

            return Double.parseDouble(number);
        } catch (Exception e) {
            System.err.println("❌ Failed to extract number after marker \"" + marker + "\": " + e.getMessage());
            return null;
        }
    }



    public Optional<LocalDate> getLastPendingInvoiceDate() {
        return pendingInvoiceRepository.findTopByOrderByDateDesc()
                .map(p -> p.getDate().toLocalDate()); // 👈 convert LocalDateTime to LocalDate
    }


    public void reprocessUnmatchedInvoices() {
        List<PendingInvoice> unmatched = pendingInvoiceRepository.findByConfirmedFalseAndTotalMatchFalse();

        // Only keep invoices that have a non-empty PDF URL
        List<PendingInvoice> validUnmatched = unmatched.stream()
                .filter(inv -> inv.getPdfUrl() != null && !inv.getPdfUrl().isEmpty())
                .toList();

        // ✅ Send full objects (not just URLs) to the Python processor
        pythonInvoiceProcessor.reprocessMismatchedInvoices(validUnmatched);
    }





}
