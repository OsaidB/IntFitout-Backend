package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.BankCheckDTO;
import life.work.IntFit.backend.service.BankCheckService;
import life.work.IntFit.backend.service.FileStorageService;
import life.work.IntFit.backend.exception.error.BadRequestException;
import life.work.IntFit.backend.exception.error.NotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
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
     * Always sorted by dueDate ASC (nulls last), then id ASC.
     */
    @GetMapping
    public List<BankCheckDTO> getAll(
            @RequestParam(required = false) Boolean cleared,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        LocalDate fromDate = parseDateOrNull(from);
        LocalDate toDate   = parseDateOrNull(to);

        return service.getAll().stream()
                .filter(c -> cleared == null || c.isCleared() == cleared)
                .filter(c -> {
                    if (c.getDueDate() == null) return true;
                    boolean ok = true;
                    if (fromDate != null) ok = ok && !c.getDueDate().isBefore(fromDate);
                    if (toDate != null)   ok = ok && !c.getDueDate().isAfter(toDate);
                    return ok;
                })
                .sorted(Comparator
                        .comparing(BankCheckDTO::getDueDate, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(BankCheckDTO::getId, Comparator.nullsLast(Comparator.naturalOrder()))
                )
                .collect(Collectors.toList());
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

    /**
     * DELETE /api/bank-checks/{id}
     * Also tries to delete the associated image from storage if imageUrl is present.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        // Best-effort fetch to clean up file
        BankCheckDTO current = null;
        try {
            // If you have service.getById(id), use it; otherwise fallback to scan.
            current = service.getAll().stream()
                    .filter(c -> Objects.equals(c.getId(), id))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {}

        if (current != null && current.getImageUrl() != null) {
            storage.deleteCheckImageByPublicUrl(current.getImageUrl());
        }

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
     * Returns simple aggregates for your dashboard (today’s tz = Asia/Hebron):
     *  {
     *    "now": "2025-09-18",
     *    "count": 12,
     *    "overdueCount": 3,
     *    "dueSoonCount": 2,  // 0..3 days
     *    "sumAll": "12345.00",
     *    "sumOverdue": "4000.00",
     *    "sumDueSoon": "1500.00"
     *  }
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        LocalDate today = LocalDate.now(HEBRON_TZ);

        List<BankCheckDTO> all = service.getAll();
        BigDecimal sumAll = sum(all);

        List<BankCheckDTO> overdue = all.stream()
                .filter(c -> isOverdue(c.getDueDate(), today))
                .toList();
        BigDecimal sumOverdue = sum(overdue);

        List<BankCheckDTO> dueSoon = all.stream()
                .filter(c -> isDueSoon(c.getDueDate(), today, 3))
                .toList();
        BigDecimal sumDueSoon = sum(dueSoon);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("now", today.toString());
        m.put("count", all.size());
        m.put("overdueCount", overdue.size());
        m.put("dueSoonCount", dueSoon.size());
        m.put("sumAll", sumAll.toPlainString());
        m.put("sumOverdue", sumOverdue.toPlainString());
        m.put("sumDueSoon", sumDueSoon.toPlainString());
        return m;
    }

    // ---------------------- helpers ----------------------

    private void validateCreate(BankCheckDTO dto) {
        if (dto == null) throw new BadRequestException("Body is required");
        if (dto.getAmount() == null) throw new BadRequestException("amount is required");
        if (dto.getDueDate() == null) throw new BadRequestException("dueDate is required");
        if (dto.getRecipientName() == null || dto.getRecipientName().isBlank())
            throw new BadRequestException("recipientName is required");
    }

    private LocalDate parseDateOrNull(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s, ISO);
        } catch (DateTimeParseException e) {
            throw new BadRequestException("Invalid date format (expected YYYY-MM-DD): " + s);
        }
    }

    private boolean isOverdue(LocalDate due, LocalDate today) {
        return due != null && due.isBefore(today);
    }

    private boolean isDueSoon(LocalDate due, LocalDate today, int days) {
        if (due == null) return false;
        long d = ChronoUnit.DAYS.between(today, due);
        return d >= 0 && d <= days;
    }

    private BigDecimal sum(Collection<BankCheckDTO> items) {
        return items.stream()
                .map(c -> c.getAmount() == null ? BigDecimal.ZERO : c.getAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
