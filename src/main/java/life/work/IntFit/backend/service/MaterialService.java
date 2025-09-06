// File: life/work/IntFit/backend/service/MaterialService.java
package life.work.IntFit.backend.service;

import life.work.IntFit.backend.dto.MaterialDTO;
import life.work.IntFit.backend.dto.PricePointDTO;
import life.work.IntFit.backend.mapper.MaterialMapper;
import life.work.IntFit.backend.model.entity.Material;
import life.work.IntFit.backend.model.enums.MaterialCategory; // ✅ correct enum
import life.work.IntFit.backend.repository.InvoiceItemRepository;
import life.work.IntFit.backend.repository.MaterialRepository;
import life.work.IntFit.backend.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import life.work.IntFit.backend.dto.MaterialWithUsageDTO;

@Service
@RequiredArgsConstructor
public class MaterialService {

    private final MaterialRepository materialRepository;
    private final InvoiceRepository invoiceRepository;
    private final MaterialMapper materialMapper;

    private final InvoiceItemRepository invoiceItemRepository;

    // ✅ Smart lookup or creation for linking invoice items
    @Transactional
    public Material findOrCreate(String name, String unit) {
        return materialRepository.findByName(name)
                .orElseGet(() -> materialRepository.save(
                        Material.builder()
                                .name(name)
                                // .unit(unit)
                                // Ensure a default category if your DB requires NOT NULL
                                .category(MaterialCategory.OTHER) // ✅ fixed
                                .build()
                ));
    }

    // ✅ RESTful service methods

    @Transactional(readOnly = true)
    public List<MaterialDTO> getAllMaterials() {
        List<Material> materials = materialRepository.findAll();
        return materialMapper.toDTOList(materials);
    }

    @Transactional(readOnly = true)
    public MaterialDTO getMaterialById(Long id) {
        Optional<Material> material = materialRepository.findById(id);
        return material.map(materialMapper::toDTO).orElse(null);
    }

    @Transactional
    public MaterialDTO addMaterial(MaterialDTO materialDTO) {
        Material material = materialMapper.toEntity(materialDTO);
        // Default category safeguard if mapper leaves it null
        if (material.getCategory() == null) {
            material.setCategory(MaterialCategory.OTHER); // ✅ fixed
        }
        Material savedMaterial = materialRepository.save(material);
        return materialMapper.toDTO(savedMaterial);
    }

    @Transactional
    public MaterialDTO updateMaterial(Long id, MaterialDTO materialDTO) {
        Optional<Material> existingMaterial = materialRepository.findById(id);
        if (existingMaterial.isPresent()) {
            Material material = existingMaterial.get();
            material.setName(materialDTO.getName());
            // material.setUnit(materialDTO.getUnit());

            // If DTO carries category, persist it (optional but handy)
            if (materialDTO.getCategory() != null) {
                material.setCategory(materialDTO.getCategory());
            }

            Material updatedMaterial = materialRepository.save(material);
            return materialMapper.toDTO(updatedMaterial);
        } else {
            return null;
        }
    }

    @Transactional
    public void deleteMaterial(Long id) {
        materialRepository.deleteById(id);
    }

    // ✅ Get material by name (for controller support)
    @Transactional(readOnly = true)
    public MaterialDTO findByName(String name) {
        return materialRepository.findByName(name)
                .map(materialMapper::toDTO)
                .orElse(null);
    }

    // ✅ Get materials sorted by usage count (for dashboard or analysis)
    @Transactional(readOnly = true)
    public List<MaterialWithUsageDTO> getMaterialsWithUsage() {
        return materialRepository.findAllWithUsageCount();
    }

    @Transactional(readOnly = true)
    public String getSampleInvoiceUrl(Long materialId) {
        return invoiceRepository.findRandomInvoiceUrlByMaterial(materialId).orElse(null);
    }

    @Transactional(readOnly = true)
    public List<PricePointDTO> getPriceHistory(Long materialId) {
        return invoiceItemRepository.findPriceHistoryByMaterialId(materialId);
    }

    // ✅ NEW: update only the category via PATCH /materials/{id}/category
    @Transactional
    public MaterialDTO updateCategory(Long id, MaterialCategory category) { // ✅ fixed signature
        Material m = materialRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Material not found: " + id));
        m.setCategory(category);
        Material saved = materialRepository.save(m);
        return materialMapper.toDTO(saved);
    }

}
