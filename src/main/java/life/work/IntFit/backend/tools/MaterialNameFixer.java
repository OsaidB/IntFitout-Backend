package life.work.IntFit.backend.tools;

import life.work.IntFit.backend.model.entity.Material;
import life.work.IntFit.backend.repository.MaterialRepository;
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

@Component
public class MaterialNameFixer implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(MaterialNameFixer.class);

    @Autowired
    private MaterialRepository materialRepository;

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
        Pageable pageable = PageRequest.of(0, 1000);

        Page<Material> page;
        do {
            page = materialRepository.findAll(pageable);
            List<Material> updatedMaterials = new ArrayList<>();

            for (Material material : page.getContent()) {
                String raw = material.getName();
                if (raw == null) {
                    logger.warn("Skipping material with null name, ID: {}", material.getId());
                    continue;
                }
                String newName = NameCleaner.clean(raw);
                if (!newName.equals(raw)) {
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
        } while (page.hasNext());

        logger.info("✅ Cleaned {} material names.", updatedCount);
    }
}