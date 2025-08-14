//package life.work.IntFit.backend.service.impl;
//
//import life.work.IntFit.backend.dto.ClientDTO;
//import life.work.IntFit.backend.dto.PetDTO;
//import life.work.IntFit.backend.exception.ResourceNotFoundException;
//import life.work.IntFit.backend.mapper.ClientMapper;
//import life.work.IntFit.backend.mapper.PetMapper;
//import life.work.IntFit.backend.model.entity.Client;
//import life.work.IntFit.backend.repository.ClientRepo;
//import life.work.IntFit.backend.service.ClientService;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.stereotype.Service;
//
//import java.util.List;
//import java.util.stream.Collectors;
//
//@Service
//public class ClientServiceImpl implements ClientService {
//
//    @Autowired
//    private ClientRepo clientRepo;
//
//    @Autowired
//    private ClientMapper clientMapper; // Injected ClientMapper instance
//
//    @Autowired
//    private PetMapper petMapper; // Injected PetMapper instance
//
//    @Override
//    public ClientDTO createClient(ClientDTO clientDTO) {
//        Client client = clientMapper.toEntity(clientDTO);
//        Client savedClient = clientRepo.save(client);
//        return clientMapper.toDTO(savedClient);
//    }
//
//    @Override
//    public ClientDTO getClientById(Long clientId) {
//        Client client = clientRepo.findById(clientId)
//                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + clientId));
//        return clientMapper.toDTO(client);
//    }
//
//    @Override
//    public List<ClientDTO> getAllClients() {
//        return clientRepo.findAll().stream()
//                .map(clientMapper::toDTO)
//                .collect(Collectors.toList());
//    }
//
//    @Override
//    public ClientDTO updateClient(Long clientId, ClientDTO clientDTO) {
//        Client client = clientRepo.findById(clientId)
//                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + clientId));
//
//        client.setFirstName(clientDTO.getFirstName());
//        client.setLastName(clientDTO.getLastName());
//        client.setEmail(clientDTO.getEmail());
//        client.setPhoneNumber(clientDTO.getPhoneNumber());
//
//        Client updatedClient = clientRepo.save(client);
//        return clientMapper.toDTO(updatedClient);
//    }
//
//    @Override
//    public void deleteClient(Long clientId) {
//        if (!clientRepo.existsById(clientId)) {
//            throw new ResourceNotFoundException("Client not found with id: " + clientId);
//        }
//        clientRepo.deleteById(clientId);
//    }
//
//    @Override
//    public List<PetDTO> getClientPets(Long clientId) {
//        Client client = clientRepo.findById(clientId)
//                .orElseThrow(() -> new ResourceNotFoundException("Client not found with id: " + clientId));
//
//        // Map the client's list of pets to PetDTO using the injected PetMapper instance
//        return client.getPets().stream()
//                .map(petMapper::toDTO)
//                .collect(Collectors.toList());
//    }
//}
