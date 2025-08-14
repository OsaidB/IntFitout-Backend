//package life.work.IntFit.backend.service.impl;
//
//import life.work.IntFit.backend.dto.PetDTO;
//import life.work.IntFit.backend.exception.ResourceNotFoundException;
//import life.work.IntFit.backend.mapper.ClientMapper;
//import life.work.IntFit.backend.mapper.PetMapper;
//import life.work.IntFit.backend.model.entity.Pet;
//import life.work.IntFit.backend.repository.PetRepository;
//import life.work.IntFit.backend.service.PetService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.util.List;
//import java.util.stream.Collectors;
//
//@Service
//public class PetServiceImpl implements PetService {
//
//    @Autowired
//    private PetRepository petRepository;
//
//    @Autowired
//    private ClientMapper clientMapper; // Injected ClientMapper instance
//
//    @Autowired
//    private PetMapper petMapper; // Injected PetMapper instance
//
//    @Override
//    public PetDTO createPet(PetDTO petDTO) {
//        Pet pet = petMapper.toEntity(petDTO); // Use the injected mapper
//        Pet savedPet = petRepository.save(pet);
//        return petMapper.toDTO(savedPet);
//    }
//
//    @Override
//    public PetDTO getPetById(Long petId) {
//        Pet pet = petRepository.findById(petId)
//                .orElseThrow(() -> new ResourceNotFoundException("Pet not found with id: " + petId));
//        return petMapper.toDTO(pet);
//    }
//
//    @Override
//    public List<PetDTO> getAllPets() {
//        return petRepository.findAll().stream()
//                .map(petMapper::toDTO)  // Use the injected mapper
//                .collect(Collectors.toList());
//    }
//
//    @Override
//    public List<PetDTO> getPetsByOwnerId(Long ownerId) {
//        return petRepository.findByOwnerId(ownerId).stream()
//                .map(petMapper::toDTO)  // Use the injected mapper
//                .collect(Collectors.toList());
//    }
//
//    @Override
//    public PetDTO updatePet(Long petId, PetDTO petDTO) {
//        Pet existingPet = petRepository.findById(petId)
//                .orElseThrow(() -> new ResourceNotFoundException("Pet not found with id: " + petId));
//
//        existingPet.setName(petDTO.getName());
//        existingPet.setType(petDTO.getType());
//        existingPet.setBreed(petDTO.getBreed());
//        existingPet.setBirthDate(petDTO.getBirthDate());
//        existingPet.setMedicalHistory(petDTO.getMedicalHistory());
//
//        Pet updatedPet = petRepository.save(existingPet);
//        return petMapper.toDTO(updatedPet);
//    }
//
//    @Override
//    public void deletePet(Long petId) {
//        if (!petRepository.existsById(petId)) {
//            throw new ResourceNotFoundException("Pet not found with id: " + petId);
//        }
//        petRepository.deleteById(petId);
//    }
//}
