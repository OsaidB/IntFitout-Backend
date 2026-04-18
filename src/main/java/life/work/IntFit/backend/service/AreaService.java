package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.AreaDTO;
import life.work.IntFit.backend.mapper.AreaMapper;
import life.work.IntFit.backend.model.entity.Area;
import life.work.IntFit.backend.model.entity.City;
import life.work.IntFit.backend.repository.AreaRepository;
import life.work.IntFit.backend.repository.CityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AreaService {

    private final AreaRepository areaRepo;
    private final CityRepository cityRepo;
    private final AreaMapper areaMapper;

    @Transactional(readOnly = true)
    public List<AreaDTO> getByCity(String cityName) {
        String trimmed = cityName == null ? "" : cityName.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("City name is required");
        }
        return areaMapper.toDTOList(areaRepo.findByCityNameIgnoreCaseOrderByNameAsc(trimmed));
    }

    @Transactional
    public AreaDTO add(String cityName, String areaName) {
        String trimmedCity = cityName == null ? "" : cityName.trim();
        String trimmedArea = areaName == null ? "" : areaName.trim();

        if (trimmedCity.isEmpty()) {
            throw new IllegalArgumentException("City name is required");
        }
        if (trimmedArea.isEmpty()) {
            throw new IllegalArgumentException("Area name cannot be empty");
        }

        City city = cityRepo.findByNameIgnoreCase(trimmedCity)
                .orElseThrow(() -> new IllegalArgumentException("Unknown city: " + trimmedCity));

        if (areaRepo.existsByCityNameIgnoreCaseAndNameIgnoreCase(trimmedCity, trimmedArea)) {
            throw new IllegalArgumentException("Area already exists: " + trimmedArea + " under " + city.getName());
        }

        Area saved = areaRepo.save(Area.builder().name(trimmedArea).city(city).build());
        return areaMapper.toDTO(saved);
    }
}