package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.ClientDTO;
import life.work.IntFit.backend.dto.PetDTO;
import life.work.IntFit.backend.service.ClientService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@CrossOrigin("*")
@AllArgsConstructor
@RestController
@RequestMapping("/api/clients")
public class ClientController {

    @Autowired
    private final ClientService clientService;

    @PostMapping
    public ResponseEntity<ClientDTO> createClient(@Valid @RequestBody ClientDTO clientDTO) {
        ClientDTO createdClient = clientService.createClient(clientDTO);
        return new ResponseEntity<>(createdClient, HttpStatus.CREATED);
    }

    @GetMapping("{clientId}")
    public ResponseEntity<ClientDTO> getClientById(@PathVariable("clientId") Long clientId) {
        ClientDTO clientDTO = clientService.getClientById(clientId);
        return ResponseEntity.ok(clientDTO);
    }

    @GetMapping
    public ResponseEntity<List<ClientDTO>> getAllClients() {
        List<ClientDTO> clients = clientService.getAllClients();
        return ResponseEntity.ok(clients);
    }

    @PutMapping("{clientId}")
    public ResponseEntity<ClientDTO> updateClient(@PathVariable("clientId") Long clientId,
                                                  @Valid @RequestBody ClientDTO updatedClient) {
        ClientDTO clientDTO = clientService.updateClient(clientId, updatedClient);
        return ResponseEntity.ok(clientDTO);
    }

    @DeleteMapping("{clientId}")
    public ResponseEntity<String> deleteClient(@PathVariable("clientId") Long clientId) {
        clientService.deleteClient(clientId);
        return ResponseEntity.ok("Client deleted successfully.");
    }

    @GetMapping("{clientId}/pets")
    public ResponseEntity<List<PetDTO>> getClientPets(@PathVariable("clientId") Long clientId) {
        List<PetDTO> pets = clientService.getClientPets(clientId);
        return ResponseEntity.ok(pets);
    }

}
