package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.MaterialUsageDTO;
import life.work.IntFit.backend.mapper.MaterialUsageMapper;
import life.work.IntFit.backend.model.entity.MaterialUsage;
import life.work.IntFit.backend.repository.MaterialUsageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MaterialUsageService {
    private final MaterialUsageRepository materialUsageRepository;
    private final MaterialUsageMapper materialUsageMapper;

    public List<MaterialUsageDTO> getAllMaterialUsages() {
        return materialUsageMapper.toDTOList(materialUsageRepository.findAll());
    }

    public MaterialUsageDTO addMaterialUsage(MaterialUsageDTO materialUsageDTO) {
        MaterialUsage materialUsage = materialUsageMapper.toEntity(materialUsageDTO);
        MaterialUsage savedMaterialUsage = materialUsageRepository.save(materialUsage);
        return materialUsageMapper.toDTO(savedMaterialUsage);
    }
}
