package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.InvoiceDTO;
import life.work.IntFit.backend.dto.InvoiceItemDTO;
import life.work.IntFit.backend.dto.PendingInvoiceDTO;
import life.work.IntFit.backend.dto.SmsMessageDTO;
import life.work.IntFit.backend.dto.SmsProcessingSummaryDTO;
import life.work.IntFit.backend.mapper.InvoiceMapper;
import life.work.IntFit.backend.mapper.PendingInvoiceItemMapper;
import life.work.IntFit.backend.mapper.PendingInvoiceMapper;
import life.work.IntFit.backend.model.entity.*;
import life.work.IntFit.backend.model.enums.StatusType;
import life.work.IntFit.backend.repository.*;
import life.work.IntFit.backend.utils.PythonInvoiceProcessor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
    private final FailedInvoiceImportRepository failedInvoiceImportRepository;
    private final InvoiceService invoiceService;

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
            StatusMessageRepository statusMessageRepository,             // ✅ AND THIS
            FailedInvoiceImportRepository failedInvoiceImportRepository, // ✅ AND THIS
            InvoiceService invoiceService                                // ✅ AND THIS (no reverse dependency: InvoiceService does not depend on PendingInvoiceService)
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
        this.failedInvoiceImportRepository = failedInvoiceImportRepository; // ✅
        this.invoiceService = invoiceService;                       // ✅
    }



    @Transactional
    public PendingInvoiceDTO savePendingInvoice(PendingInvoiceDTO dto) {
        // Map DTO → entity
        PendingInvoice pendingInvoice = pendingInvoiceMapper.toEntity(dto);
//        pendingInvoice.setParsedAt(LocalDateTime.now());
        pendingInvoice.setDate(dto.getDate());
        pendingInvoice.setConfirmed(false);
        pendingInvoice.setReceivedAtSms(dto.getReceivedAtSms());

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


    /**
     * Atomically converts a pending invoice into a final Invoice AND marks the
     * pending invoice confirmed, in a single transaction.
     * <p>
     * This replaces the previous two-call frontend flow
     * (POST /api/invoices then PATCH /pending/{id}/confirm), which was not atomic:
     * if the first call succeeded and the second failed, a final invoice existed
     * while the pending invoice stayed unconfirmed, risking a duplicate final
     * invoice on retry.
     * <p>
     * Because this method and {@link InvoiceService#saveInvoice} are both
     * {@code @Transactional} with the default REQUIRED propagation, the inner
     * call joins this same transaction: if saving the final invoice fails, the
     * pending invoice is not marked confirmed; if marking it confirmed fails,
     * the final invoice save is rolled back too.
     */
    @Transactional
    public InvoiceDTO finalizePendingInvoice(Long pendingInvoiceId) {
        PendingInvoice pending = pendingInvoiceRepository.findById(pendingInvoiceId)
                .orElseThrow(() -> new IllegalArgumentException("Pending invoice not found"));

        if (Boolean.TRUE.equals(pending.getConfirmed())) {
            throw new IllegalStateException("This invoice has already been confirmed.");
        }

        // Same field mapping the frontend currently builds by hand before calling
        // POST /api/invoices. Kept here so the mapping only needs to live in one place.
        List<InvoiceItemDTO> items = pending.getItems().stream()
                .map(item -> InvoiceItemDTO.builder()
                        .description(item.getDescription())
                        .quantity(item.getQuantity())
                        .unit_price(item.getUnit_price())
                        .total_price(item.getTotal_price())
                        .materialId(item.getMaterial() != null ? item.getMaterial().getId() : null)
                        .build())
                .toList();

        InvoiceDTO invoiceDTO = InvoiceDTO.builder()
                .date(pending.getDate())
                .netTotal(pending.getNetTotal())
                .total(pending.getTotal())
                .worksiteId(pending.getWorksite() != null ? pending.getWorksite().getId() : null)
                .worksiteName(pending.getWorksiteName())
                .total_match(pending.getTotalMatch())
                .pdfUrl(pending.getPdfUrl())
                .parsedAt(pending.getParsedAt())
                .reprocessedFromId(pending.getReprocessedFromId())
                .items(items)
                .build();

        // ✅ Reuse the existing final-invoice save logic (worksite/material matching
        // stays exactly as it is for the current POST /api/invoices flow).
        InvoiceDTO savedInvoice = invoiceService.saveInvoice(invoiceDTO);

        // Same transaction as the save above: if this throws, the final invoice
        // save is rolled back too, so no orphaned invoice is left behind.
        pending.setConfirmed(true);
        pendingInvoiceRepository.save(pending);

        System.out.println("✅ Finalized pending invoice " + pendingInvoiceId
                + " -> final invoice " + savedInvoice.getId());

        return savedInvoice;
    }


    public SmsProcessingSummaryDTO processSmsMessages(List<SmsMessageDTO> messages) {
        int totalMessages = (messages == null) ? 0 : messages.size();
        int invoiceMessages = 0;
        int statusMessages = 0;
        int unknownTypeMessages = 0;
        int invoicesProcessed = 0;
        int invoicesSkippedInvalidUrl = 0;
        int invoicesFailed = 0;
        int statusProcessed = 0;
        int statusFailed = 0;
        int failedImportsRecorded = 0;

        if (messages != null) {
            for (SmsMessageDTO message : messages) {
                String type = message.getType();

                if ("invoice".equalsIgnoreCase(type)) {
                    invoiceMessages++;
                    String content = message.getContent();

                    // ✅ Validate the URL before calling Python. Invalid/blank/non-http(s)
                    // content is skipped (no Python call, no PendingInvoice), and does not
                    // abort processing of the rest of the batch.
                    if (!isValidInvoiceUrl(content)) {
                        System.err.println("⚠️ Skipping invoice SMS with invalid/blank URL (expected http/https): "
                                + (content == null ? "null" : "\"" + content.trim() + "\""));
                        invoicesSkippedInvalidUrl++;
                        failedImportsRecorded += recordFailedImport(
                                type, content, message.getReceivedAt(), null,
                                "INVALID_INVOICE_URL", null);
                        continue;
                    }

                    // Parse the SMS timestamp separately so we can distinguish a bad
                    // timestamp (INVALID_RECEIVED_AT) from a Python/save failure.
                    String invoiceUrl = content.trim();
                    LocalDateTime smsReceivedAt;
                    try {
                        smsReceivedAt = LocalDateTime.parse(message.getReceivedAt());
                    } catch (Exception e) {
                        invoicesFailed++;
                        failedImportsRecorded += recordFailedImport(
                                type, content, message.getReceivedAt(), null,
                                "INVALID_RECEIVED_AT", e.getMessage());
                        System.err.println("❌ Invalid receivedAt for invoice SMS: " + e.getMessage());
                        continue;
                    }

                    // ✅ One failed invoice message must not abort the rest of the batch.
                    try {
                        // ✅ IMPORTANT: pass smsReceivedAt to the Python call / save flow
                        boolean ok = pythonInvoiceProcessor.sendInvoiceToPython(invoiceUrl, smsReceivedAt);
                        if (ok) {
                            invoicesProcessed++;
                        } else {
                            invoicesFailed++;
                            failedImportsRecorded += recordFailedImport(
                                    type, content, message.getReceivedAt(), smsReceivedAt,
                                    "PYTHON_PROCESSING_FAILED",
                                    "Python returned non-2xx / null body, or saving the pending invoice failed");
                        }
                    } catch (Exception e) {
                        invoicesFailed++;
                        failedImportsRecorded += recordFailedImport(
                                type, content, message.getReceivedAt(), smsReceivedAt,
                                "PYTHON_PROCESSING_FAILED", e.getMessage());
                        System.err.println("❌ Failed to process invoice SMS: " + e.getMessage());
                    }

                } else if ("status".equalsIgnoreCase(type)) {
                    statusMessages++;
                    // ✅ One failed status message must not abort the rest of the batch.
                    // Status failures are counted but intentionally NOT persisted to the
                    // invoice-failure table (this table is invoice-import focused).
                    try {
                        processStatusMessage(message);
                        statusProcessed++;
                    } catch (Exception e) {
                        statusFailed++;
                        System.err.println("❌ Failed to process status SMS: " + e.getMessage());
                    }

                } else {
                    // ✅ Unknown type is counted, skipped, and persisted for review.
                    unknownTypeMessages++;
                    failedImportsRecorded += recordFailedImport(
                            type, message.getContent(), message.getReceivedAt(), null,
                            "UNKNOWN_MESSAGE_TYPE", null);
                    System.err.println("⚠️ Skipping SMS with unknown type: "
                            + (type == null ? "null" : "\"" + type + "\""));
                }
            }
        }

        SmsProcessingSummaryDTO summary = SmsProcessingSummaryDTO.builder()
                .totalMessages(totalMessages)
                .invoiceMessages(invoiceMessages)
                .statusMessages(statusMessages)
                .unknownTypeMessages(unknownTypeMessages)
                .invoicesProcessed(invoicesProcessed)
                .invoicesSkippedInvalidUrl(invoicesSkippedInvalidUrl)
                .invoicesFailed(invoicesFailed)
                .statusProcessed(statusProcessed)
                .statusFailed(statusFailed)
                .failedImportsRecorded(failedImportsRecorded)
                .build();

        System.out.println("🧾 SMS processing summary: " + summary);
        return summary;
    }

    /**
     * Recent failed invoice imports, newest first (capped by {@code limit}).
     */
    public List<FailedInvoiceImport> getRecentFailedInvoiceImports(int limit) {
        return failedInvoiceImportRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limit));
    }

    /**
     * Persist one failed-import record. Never throws: if persisting the failure
     * record itself fails, it is logged and processing of the batch continues.
     * Returns 1 if a row was written, 0 otherwise.
     */
    private int recordFailedImport(String messageType, String rawContent, String receivedAtRaw,
                                   LocalDateTime receivedAtSms, String failureReason, String errorMessage) {
        try {
            FailedInvoiceImport record = FailedInvoiceImport.builder()
                    .messageType(messageType)
                    .contentPreview(preview(rawContent))
                    .urlHost(extractHost(rawContent))
                    .receivedAtRaw(receivedAtRaw)
                    .receivedAtSms(receivedAtSms)
                    .failureReason(failureReason)
                    .errorMessage(truncate(errorMessage, 1000))
                    .build();
            failedInvoiceImportRepository.save(record);
            return 1;
        } catch (Exception e) {
            System.err.println("❌ Could not persist failed invoice import record: " + e.getMessage());
            return 0;
        }
    }

    /** Trimmed preview of the SMS content, capped at 512 chars. */
    private static String preview(String content) {
        if (content == null) return null;
        return truncate(content.trim(), 512);
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }

    /** Host component of a URL, or null if the content is not a parseable URL. */
    private static String extractHost(String content) {
        if (content == null || content.isBlank()) return null;
        try {
            return new URI(content.trim()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Basic validation for an invoice PDF URL before it is handed to the Python converter.
     * Accepts only a parseable http/https URL with a non-blank host.
     * Intentionally minimal — this is not a full SSRF / private-IP blocker.
     */
    private boolean isValidInvoiceUrl(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            URI uri = new URI(content.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            boolean httpScheme = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
            return httpScheme && host != null && !host.isBlank();
        } catch (URISyntaxException e) {
            return false;
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

        // Original IDs that already have at least one reprocessed child invoice.
        // These originals must NOT be reprocessed again, otherwise repeated calls
        // to this endpoint would keep producing duplicate reprocessed rows.
        Set<Long> alreadyReprocessedOriginalIds =
                new HashSet<>(pendingInvoiceRepository.findAllReprocessedFromIds());

        // Base candidates: unconfirmed + mismatched, with a usable PDF URL,
        // and not themselves a reprocessed child (reprocessedFromId == null).
        List<PendingInvoice> candidates = unmatched.stream()
                .filter(inv -> inv.getPdfUrl() != null && !inv.getPdfUrl().isBlank())
                .filter(inv -> inv.getReprocessedFromId() == null)
                .toList();

        // Skip originals that already have a reprocessed child.
        List<PendingInvoice> validUnmatched = candidates.stream()
                .filter(inv -> !alreadyReprocessedOriginalIds.contains(inv.getId()))
                .toList();

        int skippedAlreadyReprocessed = candidates.size() - validUnmatched.size();
        System.out.println("🔁 Reprocess selection: unmatched=" + unmatched.size()
                + ", candidates=" + candidates.size()
                + ", skippedAlreadyReprocessed=" + skippedAlreadyReprocessed
                + ", sentToPython=" + validUnmatched.size());

        // ✅ Send full objects (not just URLs) to the Python processor
        pythonInvoiceProcessor.reprocessMismatchedInvoices(validUnmatched);
    }


    public Optional<LocalDateTime> getLastPendingInvoiceSmsDateTime(boolean onlyUnconfirmed) {
        LocalDateTime dt = onlyUnconfirmed
                ? pendingInvoiceRepository.findLatestSmsDateTimeUnconfirmed()
                : pendingInvoiceRepository.findLatestSmsDateTimeAll();
        return Optional.ofNullable(dt);
    }



}
