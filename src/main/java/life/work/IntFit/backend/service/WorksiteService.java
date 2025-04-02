package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.WorksiteDTO;
import life.work.IntFit.backend.mapper.WorksiteMapper;
import life.work.IntFit.backend.model.entity.Worksite;
import life.work.IntFit.backend.repository.WorksiteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class WorksiteService {

    private final WorksiteRepository worksiteRepository;
    private final WorksiteMapper worksiteMapper;

    public WorksiteService(WorksiteRepository worksiteRepository, WorksiteMapper worksiteMapper) {
        this.worksiteRepository = worksiteRepository;
        this.worksiteMapper = worksiteMapper;
    }

    @Transactional
    public List<WorksiteDTO> getAllWorksites() {
        List<Worksite> worksites = worksiteRepository.findAll();
        return worksiteMapper.toDTOList(worksites);
    }

    @Transactional
    public Optional<WorksiteDTO> getWorksiteById(Long id) {
        return worksiteRepository.findById(id)
                .map(worksiteMapper::toDTO);
    }

    public WorksiteDTO addWorksite(WorksiteDTO worksiteDTO) {
        Worksite worksite = worksiteMapper.toEntity(worksiteDTO);
        Worksite savedWorksite = worksiteRepository.save(worksite);
        return worksiteMapper.toDTO(savedWorksite);
    }

    public WorksiteDTO updateWorksite(Long id, WorksiteDTO updatedDTO) {
        return worksiteRepository.findById(id)
                .map(existing -> {
                    // MapStruct doesn't auto-update an existing entity, so set fields manually
                    existing.setName(updatedDTO.getName());
                    existing.setCity(updatedDTO.getCity());
                    existing.setArea(updatedDTO.getArea());
                    existing.setLocationDetails(updatedDTO.getLocationDetails());
                    existing.setType(updatedDTO.getType());
                    existing.setStatus(updatedDTO.getStatus());
                    existing.setBudget(updatedDTO.getBudget());
                    existing.setStartDate(updatedDTO.getStartDate());
                    existing.setDeadline(updatedDTO.getDeadline());
                    existing.setEndDate(updatedDTO.getEndDate());
                    existing.setDescription(updatedDTO.getDescription());
                    existing.setProgress(updatedDTO.getProgress());
                    existing.setArchived(updatedDTO.isArchived());
                    existing.setNotes(updatedDTO.getNotes());

                    Worksite updated = worksiteRepository.save(existing);
                    return worksiteMapper.toDTO(updated);
                })
                .orElseThrow(() -> new RuntimeException("Worksite not found"));
    }

    public void deleteWorksite(Long id) {
        worksiteRepository.deleteById(id);
    }
}
