// src/main/java/life/work/IntFit/backend/service/ExtraCostService.java
package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.ExtraCostDTO;
import life.work.IntFit.backend.model.entity.ExtraCost;
import life.work.IntFit.backend.repository.ExtraCostRepository;
import life.work.IntFit.backend.mapper.ExtraCostMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Service
public class ExtraCostService {
    private final ExtraCostRepository repo;
    public ExtraCostService(ExtraCostRepository repo) { this.repo = repo; }

    @Transactional(readOnly = true)
    public ResponseEntity<List<ExtraCostDTO>> byDate(LocalDate date, Long masterId) {
        var list = repo.findByDate(date, masterId).stream().map(ExtraCostMapper::toDTO).toList();
        return list.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(list);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<List<ExtraCostDTO>> general(Long masterId) {
        var list = repo.findByMasterWorksiteIdAndIsGeneralTrueOrderByIdDesc(masterId)
                .stream().map(ExtraCostMapper::toDTO).toList();
        return list.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(list);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<List<ExtraCostDTO>> inRange(Long masterId, LocalDate start, LocalDate end) {
        var list = repo.findDatedInRange(masterId, start, end).stream().map(ExtraCostMapper::toDTO).toList();
        return list.isEmpty() ? ResponseEntity.noContent().build() : ResponseEntity.ok(list);
    }

    @Transactional
    public ExtraCostDTO create(ExtraCostDTO dto) {
        validate(dto);
        var e = new ExtraCost();
        ExtraCostMapper.apply(e, dto);
        return ExtraCostMapper.toDTO(repo.save(e));
    }

    @Transactional(readOnly = true)
    public ExtraCostDTO get(Long id) {
        return repo.findById(id).map(ExtraCostMapper::toDTO).orElse(null);
    }

    @Transactional
    public ExtraCostDTO update(Long id, ExtraCostDTO dto) {
        var e = repo.findById(id).orElseThrow();
        ExtraCostMapper.apply(e, dto);
        return ExtraCostMapper.toDTO(repo.save(e));
    }

    @Transactional
    public void delete(Long id) { repo.deleteById(id); }

    private void validate(ExtraCostDTO dto) {
        if (dto.masterWorksiteId() == null) {
            throw new IllegalArgumentException("masterWorksiteId is required");
        }

        BigDecimal amt = dto.amount();
        if (amt == null) {
            throw new IllegalArgumentException("amount is required");
        }
        // ✅ allow negative, only forbid zero
        if (amt.signum() == 0) {
            throw new IllegalArgumentException("amount must not be zero");
        }

        // date rule stays the same
        if ((dto.isGeneral() == null || !dto.isGeneral()) && dto.date() == null) {
            throw new IllegalArgumentException("date required unless isGeneral=true");
        }
    }

}
