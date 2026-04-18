package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.CityDTO;
import life.work.IntFit.backend.mapper.CityMapper;
import life.work.IntFit.backend.model.entity.City;
import life.work.IntFit.backend.repository.CityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class CityService {

    private final CityRepository cityRepo;
    private final CityMapper cityMapper;

    @Transactional(readOnly = true)
    public List<CityDTO> getAll() {
        return cityMapper.toDTOList(cityRepo.findAllByOrderByNameAsc());
    }

    @Transactional
    public CityDTO add(String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("City name cannot be empty");
        }
        if (cityRepo.existsByNameIgnoreCase(trimmed)) {
            throw new IllegalArgumentException("City already exists: " + trimmed);
        }
        City saved = cityRepo.save(City.builder().name(trimmed).build());
        return cityMapper.toDTO(saved);
    }
}