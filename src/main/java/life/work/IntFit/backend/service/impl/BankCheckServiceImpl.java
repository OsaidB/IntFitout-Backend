package life.work.IntFit.backend.service.impl;

import life.work.IntFit.backend.dto.BankCheckDTO;
import life.work.IntFit.backend.mapper.BankCheckMapper;
import life.work.IntFit.backend.model.entity.BankCheck;
import life.work.IntFit.backend.repository.BankCheckRepository;
import life.work.IntFit.backend.service.BankCheckService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BankCheckServiceImpl implements BankCheckService {

    private final BankCheckRepository repo;
    private final BankCheckMapper mapper;

    @Override
    public List<BankCheckDTO> getAll() {
        return repo.findAll().stream()
                .map(mapper::toDTO)
                .collect(Collectors.toList());
    }

    @Override
    public BankCheckDTO save(BankCheckDTO dto) {
        BankCheck saved = repo.save(mapper.toEntity(dto));
        return mapper.toDTO(saved);
    }

    @Override
    public void delete(Long id) {
        repo.deleteById(id);
    }
}
