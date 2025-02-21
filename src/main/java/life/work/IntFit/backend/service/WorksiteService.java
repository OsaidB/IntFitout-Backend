package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.WorksiteDTO;
import life.work.IntFit.backend.mapper.WorksiteMapper;
import life.work.IntFit.backend.model.entity.Worksite;
import life.work.IntFit.backend.repository.WorksiteRepository;
import org.springframework.stereotype.Service;

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

    public List<WorksiteDTO> getAllWorksites() {
        List<Worksite> worksites = worksiteRepository.findAll();
        return worksiteMapper.toDTOList(worksites);
    }

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
                .map(existingWorksite -> {
                    existingWorksite.setName(updatedDTO.getName());
                    existingWorksite.setLocation(updatedDTO.getLocation());
                    existingWorksite.setStatus(updatedDTO.getStatus());
                    existingWorksite.setManager(updatedDTO.getManager());
                    existingWorksite.setBudget(updatedDTO.getBudget());
                    existingWorksite.setDeadline(updatedDTO.getDeadline());
                    Worksite updatedWorksite = worksiteRepository.save(existingWorksite);
                    return worksiteMapper.toDTO(updatedWorksite);
                }).orElseThrow(() -> new RuntimeException("Worksite not found"));
    }

    public void deleteWorksite(Long id) {
        worksiteRepository.deleteById(id);
    }
}
