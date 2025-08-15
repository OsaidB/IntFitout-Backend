package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.MasterWorksiteDTO;
import life.work.IntFit.backend.mapper.MasterWorksiteMapper;
import life.work.IntFit.backend.model.entity.MasterWorksite;
import life.work.IntFit.backend.model.entity.Worksite;
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

    public MasterWorksiteService(
            MasterWorksiteRepository masterRepo,
            WorksiteRepository worksiteRepo,
            MasterWorksiteMapper mapper
    ) {
        this.masterRepo = masterRepo;
        this.worksiteRepo = worksiteRepo;
        this.mapper = mapper;
    }

    public List<MasterWorksiteDTO> getAll() {
        return mapper.toDTOList(masterRepo.findAll());
    }

    @Transactional
    public MasterWorksiteDTO add(String name) {
        String trimmedName = name.trim();
        if (trimmedName.isEmpty()) {
            throw new IllegalArgumentException("Group name cannot be empty");
        }

        boolean exists = masterRepo.existsByApprovedNameIgnoreCase(trimmedName);
        if (exists) {
            throw new IllegalArgumentException("A group with this name already exists");
        }

        MasterWorksite entity = MasterWorksite.builder()
                .approvedName(trimmedName)
                .build();

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
}
