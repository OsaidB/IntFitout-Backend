package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.BankCheckDTO;
import life.work.IntFit.backend.exception.error.BadRequestException;
import life.work.IntFit.backend.service.BankCheckService;
import life.work.IntFit.backend.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/bank-checks")
@RequiredArgsConstructor
@CrossOrigin("*")
public class BankCheckController {

    private static final ZoneId HEBRON_TZ = ZoneId.of("Asia/Hebron");
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;

    private final BankCheckService service;
    private final FileStorageService storage;

    /**
     * GET /api/bank-checks
     * Optional filters:
     *   - cleared=[true|false]
     *   - from=YYYY-MM-DD (inclusive)
     *   - to=YYYY-MM-DD   (inclusive)
     *   - recipient=<substring, case-insensitive>
     *   - issuerPersonal=[true|false]
     *   - masterId=<source master worksite id>
     * Always sorted by dueDate ASC (nulls last), then id ASC.
     */
    @GetMapping
    public List<BankCheckDTO> getAll(
            @RequestParam(required = false) Boolean cleared,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String recipient,
            @RequestParam(required = false, name = "issuerPersonal") Boolean issuerPersonal,
            @RequestParam(required = false, name = "masterId") Long masterId
    ) {
        LocalDate fromDate = parseDateOrNull(from);
        LocalDate toDate   = parseDateOrNull(to);

        return service.getAll(cleared, fromDate, toDate).stream()
                .filter(c -> recipient == null || containsIgnoreCase(c.getRecipientName(), recipient))
                .filter(c -> issuerPersonal == null || Boolean.TRUE.equals(c.getPersonalIssuer()) == issuerPersonal)
                .filter(c -> masterId == null || Objects.equals(c.getSourceMasterWorksiteId(), masterId))
                .sorted(Comparator
                        .comparing(BankCheckDTO::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(BankCheckDTO::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                )
                .collect(Collectors.toList());
    }

    /** GET /api/bank-checks/{id} */
    @GetMapping("/{id}")
    public BankCheckDTO getOne(@PathVariable Long id) {
        return service.getById(id);
    }

    /**
     * POST /api/bank-checks
     * Creates a new bank check.
     */
    @PostMapping
    public ResponseEntity<BankCheckDTO> create(@RequestBody BankCheckDTO dto) {
        validateCreate(dto);
        BankCheckDTO saved = service.save(dto);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}")
                .buildAndExpand(saved.getId())
                .toUri();
        return ResponseEntity.created(location).body(saved);
    }

    /** PATCH /api/bank-checks/{id} (partial update) */
    @PatchMapping("/{id}")
    public BankCheckDTO patch(@PathVariable Long id, @RequestBody BankCheckDTO patch) {
        // amount/dueDate/recipientName can be omitted in patch (mapper ignores nulls)
        return service.update(id, patch);
    }

    /**
     * DELETE /api/bank-checks/{id}
     * (File deletion is handled in the service.)
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/bank-checks/upload-image
     * Accepts multipart image in field "file". Returns: { "url": "https://.../uploads/checks/<file>" }
     */
    @PostMapping("/upload-image")
    public Map<String, String> uploadImage(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BadRequestException("Empty file");
        }
        String filename = storage.saveCheckImage(file);
        String base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString();
        String url = base + "/uploads/checks/" + filename;
        return Collections.singletonMap("url", url);
    }

    /**
     * GET /api/bank-checks/stats
     * Delegates to service.stats() which includes:
     *  - now, count, overdueCount, dueSoonCount, sums
     *  - sumToEtimadNonPersonal (deduct now)
     *  - sumToEtimadPersonal    (do NOT deduct)
     *  - sumToOthers
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        return service.stats();
    }

    // ---------------------- helpers ----------------------

    private void validateCreate(BankCheckDTO dto) {
        if (dto == null) throw new BadRequestException("Body is required");
        if (dto.getAmount() == null) throw new BadRequestException("amount is required");
        if (dto.getDueDate() == null) throw new BadRequestException("dueDate is required");
        if (dto.getRecipientName() == null || dto.getRecipientName().isBlank())
            throw new BadRequestException("recipientName is required");
        // issuer/worksite are optional by design
    }

    private LocalDate parseDateOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s, ISO);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Invalid date format (expected YYYY-MM-DD): " + s);
        }
    }

    private boolean containsIgnoreCase(String haystack, String needle) {
        if (needle == null || needle.isBlank()) return true;
        if (haystack == null) return false;
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    // Keeping these for any potential reuse (if you revert to local stats calc):
    @SuppressWarnings("unused")
    private boolean isOverdue(LocalDate due, LocalDate today) {
        return due != null && due.isBefore(today);
    }

    @SuppressWarnings("unused")
    private boolean isDueSoon(LocalDate due, LocalDate today, int days) {
        if (due == null) return false;
        long d = java.time.temporal.ChronoUnit.DAYS.between(today, due);
        return d >= 0 && d <= days;
    }

    @SuppressWarnings("unused")
    private BigDecimal sum(Collection<BankCheckDTO> items) {
        return items.stream()
                .map(c -> c.getAmount() == null ? BigDecimal.ZERO : c.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
