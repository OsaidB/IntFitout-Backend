package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.PendingInvoiceDTO;
import life.work.IntFit.backend.dto.SmsMessageDTO;
import life.work.IntFit.backend.mapper.InvoiceMapper;
import life.work.IntFit.backend.mapper.PendingInvoiceItemMapper;
import life.work.IntFit.backend.mapper.PendingInvoiceMapper;
import life.work.IntFit.backend.model.entity.*;
import life.work.IntFit.backend.model.enums.StatusType;
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
        // Map DTO → entity
        PendingInvoice pendingInvoice = pendingInvoiceMapper.toEntity(dto);
//        pendingInvoice.setParsedAt(LocalDateTime.now());
        pendingInvoice.setDate(dto.getDate());
        pendingInvoice.setConfirmed(false);

        // ✅ Set reprocessedFromId directly (NO object reference!)
        pendingInvoice.setReprocessedFromId(dto.getReprocessedFromId());

        // Map items + ensure material existence
        var items = dto.getItems().stream().map(itemDTO -> {
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
    public void confirmPendingInvoice(Long pendingInvoiceId) {
        PendingInvoice pending = pendingInvoiceRepository.findById(pendingInvoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Pending invoice not found"));

        if (Boolean.TRUE.equals(pending.getConfirmed())) {
            throw new IllegalStateException("This invoice has already been confirmed.");
        }

        pending.setConfirmed(true);
        pendingInvoiceRepository.save(pending);
    }


    public void processSmsMessages(List<SmsMessageDTO> messages) {
        for (SmsMessageDTO message : messages) {
            if ("invoice".equalsIgnoreCase(message.getType())) {
                pythonInvoiceProcessor.sendInvoiceToPython(message.getContent());
            } else if ("status".equalsIgnoreCase(message.getType())) {
                processStatusMessage(message);
            }
        }
    }

    private void processStatusMessage(SmsMessageDTO message) {
        String content = message.getContent();
        LocalDateTime receivedAt = LocalDateTime.parse(message.getReceivedAt());

        String type = classifyStatusType(content);

        Double amount = null;
//        Double totalOwed = extractArabicNumber(content, "رصيدكم");
        LocalDate balanceDate = null;

        Double totalOwed = type.equals("BALANCE_AT_DATE")
                ? extractArabicNumber(content, "هو")
                : extractArabicNumber(content, "رصيدكم");

        switch (type) {
            case "BALANCE_AT_DATE" -> {
                balanceDate = extractDate(content);
            }
            case "ORDER_ISSUED" -> {
                amount = extractArabicNumber(content, "بمبلغ");
            }
            case "PAYMENT" -> {
                amount = extractArabicNumber(content, "بمبلغ");
                if (amount == null) {
                    amount = extractArabicNumber(content, "مبلغ");
                }
                if (amount != null) amount *= -1; // Payment is negative
            }
            case "RETURN" -> {
                amount = extractArabicNumber(content, "بقيمة");
                if (amount != null) amount *= -1; // Return is negative
            }
        }

        StatusMessage status = StatusMessage.builder()
                .content(content)
                .receivedAt(receivedAt)
                .amount(amount)
                .totalOwed(totalOwed)
                .statusType(StatusType.valueOf(type))
                .balanceDate(balanceDate)
                .build();

        statusMessageRepository.save(status);
    }

    private String classifyStatusType(String content) {
        if (content.contains("رصيدكم لغاية")) return "BALANCE_AT_DATE";
        if (content.contains("اصدار طلبيه")) return "ORDER_ISSUED";
        if (content.contains("مرتجع بضاعة")) return "RETURN";
        if (content.contains("شكرا لسداد")) return "PAYMENT";
        return "UNKNOWN";
    }

    private LocalDate extractDate(String content) {
        try {
            var matcher = java.util.regex.Pattern
                    .compile("(\\d{1,2}/\\d{1,2}/\\d{4})")
                    .matcher(content);

            if (matcher.find()) {
                String dateStr = matcher.group(1);
                var formatter = java.time.format.DateTimeFormatter.ofPattern("d/M/yyyy");
                return LocalDate.parse(dateStr, formatter);
            }
        } catch (Exception e) {
            System.err.println("❌ Failed to extract date: " + e.getMessage());
        }
        return null;
    }


    private Double extractArabicNumber(String text, String marker) {
        try {
            // Match marker with optional space, followed by number
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                    java.util.regex.Pattern.quote(marker) + "\\s*(\\d+(\\.\\d+)?)"
            );

            java.util.regex.Matcher matcher = pattern.matcher(text);
            if (matcher.find()) {
                return Double.parseDouble(matcher.group(1));
            }

            return null;
        } catch (Exception e) {
            System.err.println("❌ Failed to extract number after marker \"" + marker + "\": " + e.getMessage());
            return null;
        }
    }


    /**
     * Legacy: latest business LocalDate among ALL pending invoices.
     */

    public Optional<LocalDate> getLastPendingInvoiceDate() {
        return Optional.ofNullable(pendingInvoiceRepository.findLatestBusinessDateAll());
    }


    /**
     * Latest business LocalDate with optional unconfirmed-only filter.
     */
    public Optional<LocalDate> getLastPendingInvoiceBusinessDate(boolean onlyUnconfirmed) {
        LocalDate d = onlyUnconfirmed
                ? pendingInvoiceRepository.findLatestBusinessDateUnconfirmed()
                : pendingInvoiceRepository.findLatestBusinessDateAll();
        return Optional.ofNullable(d);
    }

    // =========================
    // NEW: LocalDateTime (with time)
    // =========================

    /**
     * Latest business LocalDateTime among pending invoices.
     * Set onlyUnconfirmed=true to look only at unconfirmed rows.
     */
    public Optional<LocalDateTime> getLastPendingInvoiceDateTime(boolean onlyUnconfirmed) {
        LocalDateTime dt = onlyUnconfirmed
                ? pendingInvoiceRepository.findLatestBusinessDateTimeUnconfirmed()
                : pendingInvoiceRepository.findLatestBusinessDateTimeAll();
        return Optional.ofNullable(dt);
    }

    public void reprocessUnmatchedInvoices() {
        List<PendingInvoice> unmatched = pendingInvoiceRepository.findByConfirmedFalseAndTotalMatchFalse();

        // Only keep invoices that have a non-empty PDF URL and weren't reprocessed already
        List<PendingInvoice> validUnmatched = unmatched.stream()
                .filter(inv -> inv.getPdfUrl() != null && !inv.getPdfUrl().isEmpty())
                .filter(inv -> inv.getReprocessedFromId() == null)
                .toList();

        // ✅ Send full objects (not just URLs) to the Python processor
        pythonInvoiceProcessor.reprocessMismatchedInvoices(validUnmatched);
    }





}
