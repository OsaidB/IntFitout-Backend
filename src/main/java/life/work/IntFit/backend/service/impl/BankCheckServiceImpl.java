package life.work.IntFit.backend.service.impl;

import life.work.IntFit.backend.dto.BankCheckDTO;
import life.work.IntFit.backend.exception.error.BadRequestException;
import life.work.IntFit.backend.exception.error.NotFoundException;
import life.work.IntFit.backend.mapper.BankCheckMapper;
import life.work.IntFit.backend.model.entity.BankCheck;
import life.work.IntFit.backend.repository.BankCheckRepository;
import life.work.IntFit.backend.service.BankCheckService;
import life.work.IntFit.backend.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class BankCheckServiceImpl implements BankCheckService {

    private static final ZoneId HEBRON_TZ = ZoneId.of("Asia/Hebron");

    private final BankCheckRepository repo;
    private final BankCheckMapper mapper;
    private final FileStorageService storage;

    // ---------- queries ----------

    @Override
    public List<BankCheckDTO> getAll() {
        return repo.findAll(defaultSort())
                .stream()
                .map(mapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<BankCheckDTO> getAll(Boolean cleared, LocalDate from, LocalDate to) {
        return repo.findAll(defaultSort())
                .stream()
                .map(mapper::toDto)
                .filter(c -> cleared == null || c.isCleared() == cleared)
                .filter(c -> {
                    if (c.getDueDate() == null) return true;
                    boolean ok = true;
                    if (from != null) ok = ok && !c.getDueDate().isBefore(from);
                    if (to != null)   ok = ok && !c.getDueDate().isAfter(to);
                    return ok;
                })
                .collect(Collectors.toList());
    }

    @Override
    public BankCheckDTO getById(Long id) {
        BankCheck entity = repo.findById(id)
                .orElseThrow(() -> new NotFoundException("BankCheck not found: " + id));
        return mapper.toDto(entity);
    }

    // ---------- commands ----------

    @Override
    @Transactional
    public BankCheckDTO save(BankCheckDTO dto) {
        validateCreate(dto);

        BankCheck entity = mapper.toEntity(dto);
        entity.setId(null);
        normalizeAmount(entity);       // <-- scale(2)
        validateInvariant(entity);     // <-- BigDecimal checks

        BankCheck saved = repo.save(entity);
        return mapper.toDto(saved);
    }

    @Override
    @Transactional
    public BankCheckDTO update(Long id, BankCheckDTO patch) {
        if (id == null) throw new BadRequestException("id is required");

        BankCheck entity = repo.findById(id)
                .orElseThrow(() -> new NotFoundException("BankCheck not found: " + id));

        // Apply only non-null fields from DTO
        mapper.update(entity, patch);

        // bring amount to 2 decimals if present
        normalizeAmount(entity);
        validateInvariant(entity);

        BankCheck saved = repo.save(entity);
        return mapper.toDto(saved);
    }

    @Override
    @Transactional
    public void delete(Long id) {
        BankCheck entity = repo.findById(id)
                .orElseThrow(() -> new NotFoundException("BankCheck not found: " + id));

        // Best-effort file cleanup
        if (entity.getImageUrl() != null) {
            try {
                storage.deleteCheckImageByPublicUrl(entity.getImageUrl());
            } catch (Exception ignored) { /* don’t fail deletion for storage issues */ }
        }

        repo.delete(entity);
    }

    // ---------- aggregates ----------

    @Override
    public Map<String, Object> stats() {
        LocalDate today = LocalDate.now(HEBRON_TZ);
        List<BankCheckDTO> all = getAll();

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

    // ---------- helpers ----------

    private Sort defaultSort() {
        return Sort.by(Sort.Direction.ASC, "dueDate")
                .and(Sort.by(Sort.Direction.ASC, "id"));
    }

    private void validateCreate(BankCheckDTO dto) {
        if (dto == null) throw new BadRequestException("Body is required");
        if (dto.getAmount() == null) throw new BadRequestException("amount is required");
        if (dto.getAmount().compareTo(BigDecimal.ZERO) <= 0)
            throw new BadRequestException("amount must be > 0");
        if (dto.getDueDate() == null) throw new BadRequestException("dueDate is required");
        if (dto.getRecipientName() == null || dto.getRecipientName().isBlank())
            throw new BadRequestException("recipientName is required");
    }

    /** Enforce invariants after PATCH and before save. */
    private void validateInvariant(BankCheck e) {
        if (e.getAmount() == null) {
            throw new BadRequestException("amount is required");
        }
        if (e.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("amount must be > 0");
        }
    }

    /** Always store amounts as scale(2), HALF_UP. */
    private void normalizeAmount(BankCheck e) {
        if (e.getAmount() != null) {
            e.setAmount(e.getAmount().setScale(2, RoundingMode.HALF_UP));
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
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }
}
