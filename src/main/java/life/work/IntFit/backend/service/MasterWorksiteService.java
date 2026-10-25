package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.MasterWorksiteDTO;
import life.work.IntFit.backend.mapper.MasterWorksiteMapper;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.model.entity.Worksite;
import life.work.IntFit.backend.repository.AreaRepository;
import life.work.IntFit.backend.repository.CityRepository;
import life.work.IntFit.backend.repository.MasterWorksiteRepository;
import life.work.IntFit.backend.repository.WorksiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class MasterWorksiteService {

    private final MasterWorksiteRepository masterRepo;
    private final WorksiteRepository worksiteRepo;
    private final MasterWorksiteMapper mapper;
    private final CityRepository cityRepo;
    private final AreaRepository areaRepo;

    public MasterWorksiteService(
            MasterWorksiteRepository masterRepo,
            WorksiteRepository worksiteRepo,
            MasterWorksiteMapper mapper,
            CityRepository cityRepo,
            AreaRepository areaRepo
    ) {
        this.masterRepo = masterRepo;
        this.worksiteRepo = worksiteRepo;
        this.mapper = mapper;
        this.cityRepo = cityRepo;
        this.areaRepo = areaRepo;
    }

    @Transactional(readOnly = true)
    public List<MasterWorksiteDTO> getAll() {
        return mapper.toDTOList(masterRepo.findAll());
    }

    /** ➕ NEW: fetch single master worksite by ID */
    @Transactional(readOnly = true)
    public MasterWorksiteDTO getById(Long id) {
        MasterWorksite entity = masterRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Master worksite not found"));
        return mapper.toDTO(entity);
    }

    @Transactional
    public MasterWorksiteDTO add(MasterWorksiteDTO dto) {
        String trimmedName = dto.getApprovedName() == null ? "" : dto.getApprovedName().trim();
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("Group name cannot be empty");
        }

        boolean exists = masterRepo.existsByApprovedNameIgnoreCase(trimmedName);
        if (exists) {
            throw new IllegalArgumentException("A group with this name already exists");
        }

        String canonicalCity = resolveCanonicalCity(dto.getCity());
        String canonicalArea = resolveCanonicalArea(canonicalCity, dto.getArea());

        MasterWorksite entity = MasterWorksite.builder()
                .approvedName(trimmedName)
                .nickname(normalizeNickname(dto.getNickname()))
                .notes(dto.getNotes())
                .city(canonicalCity)
                .area(canonicalArea)
                .subArea(dto.getSubArea())
                .locationDetails(dto.getLocationDetails())
                .projectSizeTier(dto.getProjectSizeTier())
                .estimatedAreaM2(dto.getEstimatedAreaM2())
                .build();

        return mapper.toDTO(masterRepo.save(entity));
    }

    @Transactional
    public MasterWorksiteDTO update(Long id, MasterWorksiteDTO dto) {
        MasterWorksite entity = masterRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Master worksite not found"));
        if (dto.getNickname()!=null) entity.setNickname(normalizeNickname(dto.getNickname()));

        if (dto.getApprovedName() != null) {
            String trimmedName = dto.getApprovedName().trim();
            if (trimmedName.isEmpty()) {
                throw new IllegalArgumentException("Approved name cannot be empty");
            }
            if (!entity.getApprovedName().equalsIgnoreCase(trimmedName) &&
                    masterRepo.existsByApprovedNameIgnoreCase(trimmedName)) {
                throw new IllegalArgumentException("A group with this name already exists");
            }
            entity.setApprovedName(trimmedName);
        }

        String canonicalCity = resolveCanonicalCity(dto.getCity());
        String canonicalArea = resolveCanonicalArea(canonicalCity, dto.getArea());

        entity.setNotes(dto.getNotes());
        entity.setCity(canonicalCity);
        entity.setArea(canonicalArea);
        entity.setSubArea(dto.getSubArea());
        entity.setLocationDetails(dto.getLocationDetails());
        entity.setProjectSizeTier(dto.getProjectSizeTier());
        entity.setEstimatedAreaM2(dto.getEstimatedAreaM2());

        return mapper.toDTO(masterRepo.save(entity));
    }

    @Transactional
    public void assignWorksites(Long masterId, List<Long> worksiteIds) {
        MasterWorksite master = masterRepo.findById(masterId)
                .orElseThrow(() -> new IllegalArgumentException("Master worksite not found"));

        if (worksiteIds == null || worksiteIds.contains(null)) {
            throw new IllegalArgumentException("Worksite IDs list contains null");
        }

        List<Worksite> worksites = worksiteRepo.findAllById(worksiteIds);

        if (worksites.size() != worksiteIds.size()) {
            throw new IllegalArgumentException("Some worksite IDs were not found");
        }

        for (Worksite worksite : worksites) {
            worksite.setMasterWorksite(master);
        }

        worksiteRepo.saveAll(worksites);
    }

    @Transactional
    public MasterWorksiteDTO updateNotes(Long id, String notes) {
        MasterWorksite entity = masterRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Master worksite not found"));

        String normalized = notes == null ? "" : notes.trim();
        entity.setNotes(normalized);

        return mapper.toDTO(masterRepo.save(entity));
    }

    /** Narrow edit, preserving notes, approved name, location, size and child relations. */
    @Transactional
    public MasterWorksiteDTO updateNickname(Long id,String nickname) {
        MasterWorksite entity=masterRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,"Master worksite not found"));
        entity.setNickname(normalizeNickname(nickname));
        return mapper.toDTO(masterRepo.save(entity));
    }
    public static String normalizeNickname(String value) {
        if(value==null || value.isBlank()) return null;
        String s=value.strip();
        // UTF-16 bound is deliberately conservative for both database and mobile clients.
        if(s.length()>80 || s.codePoints().anyMatch(c -> Character.isISOControl(c) || Character.getType(c)==Character.FORMAT || Character.getType(c)==Character.LINE_SEPARATOR || Character.getType(c)==Character.PARAGRAPH_SEPARATOR))
            throw new IllegalArgumentException("Nickname must be at most 80 characters without control characters.");
        return s;
    }

    /** ➕ NEW: delete master worksite (detaches children first to avoid FK issues) */
    @Transactional
    public void delete(Long id) {
        MasterWorksite entity = masterRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Master worksite not found"));

        // If Worksite has a FK to MasterWorksite, detach referencing rows first.
        // Ensure WorksiteRepository has: List<Worksite> findByMasterWorksiteId(Long masterId);
        List<Worksite> attached = worksiteRepo.findByMasterWorksiteId(id);
        if (attached != null && !attached.isEmpty()) {
            for (Worksite ws : attached) {
                ws.setMasterWorksite(null);
            }
            worksiteRepo.saveAll(attached);
        }

        masterRepo.delete(entity);
    }

    /**
     * Returns the canonical City.name from the database, or null if city is blank/null.
     * Throws if the city is provided but not found.
     */
    private String resolveCanonicalCity(String city) {
        if (city == null || city.isBlank()) return null;
        return cityRepo.findByNameIgnoreCase(city.trim())
                .map(c -> c.getName())
                .orElseThrow(() -> new IllegalArgumentException("Unknown city: " + city.trim()));
    }

    private String resolveCanonicalArea(String canonicalCity, String area) {
        if (area == null || area.isBlank()) return null;
        if (canonicalCity == null || canonicalCity.isBlank()) {
            throw new IllegalArgumentException("City must be provided when area is specified");
        }
        return areaRepo.findByCityNameIgnoreCaseAndNameIgnoreCase(canonicalCity, area.trim())
                .map(a -> a.getName())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown area '" + area.trim() + "' under city '" + canonicalCity + "'"));
    }
}
