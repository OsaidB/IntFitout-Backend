package life.work.IntFit.backend.tools;

import life.work.IntFit.backend.model.entity.Material;
import life.work.IntFit.backend.model.entity.InvoiceItem;
import life.work.IntFit.backend.repository.MaterialRepository;
import life.work.IntFit.backend.repository.InvoiceItemRepository;
import life.work.IntFit.backend.repository.PendingInvoiceItemRepository;
import life.work.IntFit.backend.utils.NameCleaner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class MaterialNameFixer implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(MaterialNameFixer.class);

    @Autowired
    private MaterialRepository materialRepository;

    @Autowired
    private InvoiceItemRepository invoiceItemRepository;

    @Autowired
    private PendingInvoiceItemRepository pendingInvoiceItemRepository;



    @Value("${material.name.cleaner.enabled:false}")
    private boolean cleanerEnabled;

    @Override
    @Transactional
    public void run(String... args) throws Exception {
        if (!cleanerEnabled) {
            logger.info("Material name cleaner is disabled.");
            return;
        }

        int updatedCount = 0;
        int mergedCount = 0;
        Pageable pageable = PageRequest.of(0, 1000);
        Page<Material> page = materialRepository.findAll(pageable);

//        Page<Material> page;
        do {
//            page = materialRepository.findAll(pageable);
            List<Material> updatedMaterials = new ArrayList<>();

            for (Material material : page.getContent()) {
                String raw = material.getName();
                if (raw == null) {
                    logger.warn("Skipping material with null name, ID: {}", material.getId());
                    continue;
                }
                String newName = NameCleaner.clean(raw);

                // Check if the new name already exists
                Optional<Material> existingMaterial = materialRepository.findByName(newName);
                if (existingMaterial.isPresent() && !existingMaterial.get().getId().equals(material.getId())) {



                    // Merge logic: Update invoiceItems and delete the old material
                    Long targetId = existingMaterial.get().getId();
                    Long sourceId = material.getId();

                    int updatedItems = invoiceItemRepository.updateMaterialId(sourceId, targetId);
                    logger.info("✅ Reassigned {} invoice items from material {} → {}", updatedItems, sourceId, targetId);

                    int updatedPendingItems = pendingInvoiceItemRepository.updateMaterialId(sourceId, targetId);
                    logger.info("✅ Reassigned {} pending invoice items from material {} → {}", updatedPendingItems, sourceId, targetId);

                    materialRepository.delete(material);
                    mergedCount++;
                    logger.info("Merged material ID {} into ID {} with name {}", sourceId, targetId, newName);

                } else if (!newName.equals(raw)) {
                    // Update with new unique name
                    material.setName(newName);
                    updatedMaterials.add(material);
                    updatedCount++;
                }
            }
            int skipped = (int) page.getContent().stream()
                    .filter(m -> m.getName() == null || NameCleaner.clean(m.getName()).equals(m.getName()))
                    .count();

            logger.info("⏭️ Skipped {} materials (already clean or null).", skipped);

            try {
                materialRepository.saveAll(updatedMaterials);
            } catch (Exception e) {
                logger.error("Failed to save updated materials: {}", e.getMessage(), e);
                throw e;
            }

            pageable = pageable.next();
            page = materialRepository.findAll(pageable);
        } while (page.hasNext());

        logger.info("✅ Cleaned {} material names and merged {} materials.", updatedCount, mergedCount);
    }
}