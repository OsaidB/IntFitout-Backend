package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.MaterialDTO;
import life.work.IntFit.backend.mapper.MaterialMapper;
import life.work.IntFit.backend.model.entity.Material;
import life.work.IntFit.backend.repository.MaterialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MaterialService {

    private final MaterialRepository materialRepository;
    private final MaterialMapper materialMapper;

    // ✅ Smart lookup or creation for linking invoice items
    public Material findOrCreate(String name, String unit) {
        return materialRepository.findByName(name)
                .orElseGet(() -> materialRepository.save(
                        Material.builder()
                                .name(name)
//                                .unit(unit)
                                .build()
                ));
    }

    // ✅ RESTful service methods

    public List<MaterialDTO> getAllMaterials() {
        List<Material> materials = materialRepository.findAll();
        return materialMapper.toDTOList(materials);
    }

    public MaterialDTO getMaterialById(Long id) {
        Optional<Material> material = materialRepository.findById(id);
        return material.map(materialMapper::toDTO).orElse(null);
    }

    public MaterialDTO addMaterial(MaterialDTO materialDTO) {
        Material material = materialMapper.toEntity(materialDTO);
        Material savedMaterial = materialRepository.save(material);
        return materialMapper.toDTO(savedMaterial);
    }

    public MaterialDTO updateMaterial(Long id, MaterialDTO materialDTO) {
        Optional<Material> existingMaterial = materialRepository.findById(id);
        if (existingMaterial.isPresent()) {
            Material material = existingMaterial.get();
            material.setName(materialDTO.getName());
//            material.setUnit(materialDTO.getUnit());
            Material updatedMaterial = materialRepository.save(material);
            return materialMapper.toDTO(updatedMaterial);
        } else {
            return null;
        }
    }

    public void deleteMaterial(Long id) {
        materialRepository.deleteById(id);
    }

    // ✅ Get material by name (for controller support)
    public MaterialDTO findByName(String name) {
        return materialRepository.findByName(name)
                .map(materialMapper::toDTO)
                .orElse(null);
    }
}
