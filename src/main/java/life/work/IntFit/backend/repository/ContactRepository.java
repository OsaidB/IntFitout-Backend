package life.work.IntFit.backend.repository;

import life.work.IntFit.backend.model.entity.Contact;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactRepository extends JpaRepository<Contact, Long> {}
