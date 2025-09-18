package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.BankCheckDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface BankCheckService {

    /** Backward-compatible: returns all, sorted by dueDate ASC then id ASC. */
    List<BankCheckDTO> getAll();

    /** With optional filters; if a param is null it’s ignored. */
    List<BankCheckDTO> getAll(Boolean cleared, LocalDate from, LocalDate to);

    BankCheckDTO getById(Long id);

    /** Create (controller currently calls this). */
    BankCheckDTO save(BankCheckDTO dto);

    /** PATCH-like update: only non-null fields are applied. */
    BankCheckDTO update(Long id, BankCheckDTO patch);

    void delete(Long id);

    /** Simple aggregates for dashboards. */
    Map<String, Object> stats();
}
