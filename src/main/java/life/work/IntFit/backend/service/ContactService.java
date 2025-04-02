package life.work.IntFit.backend.service;

import life.work.IntFit.backend.model.entity.Contact;
import life.work.IntFit.backend.repository.ContactRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

@Service
public class ContactService {

    private final ContactRepository contactRepository;

    public ContactService(ContactRepository contactRepository) {
        this.contactRepository = contactRepository;
    }

    public List<Contact> getAllContacts() {
        return contactRepository.findAll();
    }

    public Optional<Contact> getContactById(Long id) {
        return contactRepository.findById(id);
    }

    public Contact addContact(Contact contact) {
        return contactRepository.save(contact);
    }

    public Contact updateContact(Long id, Contact updatedContact) {
        return contactRepository.findById(id)
                .map(existing -> {
                    existing.setName(updatedContact.getName());
                    existing.setPhone(updatedContact.getPhone());
                    existing.setType(updatedContact.getType());
                    return contactRepository.save(existing);
                })
                .orElseThrow(() -> new IllegalArgumentException("Contact not found with ID: " + id));
    }

    public void deleteContact(Long id) {
        contactRepository.deleteById(id);
    }
}
