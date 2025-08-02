package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.BankCheckDTO;

import java.util.List;

public interface BankCheckService {
    List<BankCheckDTO> getAll();
    BankCheckDTO save(BankCheckDTO dto);
    void delete(Long id);
}
