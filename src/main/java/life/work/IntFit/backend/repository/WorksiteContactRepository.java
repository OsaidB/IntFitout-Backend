package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.Contact;
import life.work.IntFit.backend.model.entity.WorksiteContact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorksiteContactRepository extends JpaRepository<WorksiteContact, Long> {
    List<WorksiteContact> findByWorksiteId(Long worksiteId);
}
