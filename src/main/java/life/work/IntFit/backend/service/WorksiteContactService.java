package life.work.IntFit.backend.service;

import life.work.IntFit.backend.model.entity.Contact;
import life.work.IntFit.backend.model.entity.Worksite;
import life.work.IntFit.backend.model.entity.WorksiteContact;
import life.work.IntFit.backend.repository.ContactRepository;
import life.work.IntFit.backend.repository.WorksiteContactRepository;
import life.work.IntFit.backend.repository.WorksiteRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class WorksiteContactService {

    private final WorksiteContactRepository worksiteContactRepository;
    private final WorksiteRepository worksiteRepository;
    private final ContactRepository contactRepository;

    public WorksiteContactService(WorksiteContactRepository worksiteContactRepository,
                                  WorksiteRepository worksiteRepository,
                                  ContactRepository contactRepository) {
        this.worksiteContactRepository = worksiteContactRepository;
        this.worksiteRepository = worksiteRepository;
        this.contactRepository = contactRepository;
    }

    public List<WorksiteContact> getAllByWorksiteId(Long worksiteId) {
        return worksiteContactRepository.findByWorksiteId(worksiteId);
    }

    public WorksiteContact addWorksiteContact(Long worksiteId, Long contactId, String note) {
        Worksite worksite = worksiteRepository.findById(worksiteId)
                .orElseThrow(() -> new IllegalArgumentException("Worksite not found"));

        Contact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new IllegalArgumentException("Contact not found"));

        WorksiteContact worksiteContact = WorksiteContact.builder()
                .worksite(worksite)
                .contact(contact)
                .note(note)
                .build();

        return worksiteContactRepository.save(worksiteContact);
    }

    public void deleteWorksiteContact(Long id) {
        worksiteContactRepository.deleteById(id);
    }
}
