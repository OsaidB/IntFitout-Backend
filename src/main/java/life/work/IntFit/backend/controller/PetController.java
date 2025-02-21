package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.PetDTO;
import life.work.IntFit.backend.service.PetService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

@CrossOrigin("*")
@AllArgsConstructor
@RestController
@RequestMapping("/api/pets")
public class PetController {

    @Autowired
    private final PetService petService;

    // Directory to save uploaded images
    private static final String UPLOAD_DIR = "uploads/";

    @PostMapping
    public ResponseEntity<PetDTO> createPet(@Valid @RequestBody PetDTO petDTO) {
        PetDTO createdPet = petService.createPet(petDTO);
        return new ResponseEntity<>(createdPet, HttpStatus.CREATED);
    }

    @PostMapping("/uploadImage")
    public ResponseEntity<String> uploadPetImage(@RequestParam("image") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body("File is empty");
            }

            // Generate unique file name
            String fileName = System.currentTimeMillis() + "-" + file.getOriginalFilename();
            Path filePath = Paths.get(UPLOAD_DIR + fileName);

            // Ensure upload directory exists
            Files.createDirectories(filePath.getParent());

            // Save the file to the upload directory
            Files.write(filePath, file.getBytes());

            // Construct the URL to access the image
            String imageUrl = "/uploads/" + fileName;

            return ResponseEntity.ok(imageUrl);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to upload image");
        }
    }

    @GetMapping("/uploads/{fileName}")
    public ResponseEntity<Resource> serveImage(@PathVariable String fileName) {
        try {
            // Define the path to the file in the upload directory
            Path filePath = Paths.get(UPLOAD_DIR).resolve(fileName).normalize();
            Resource resource = new UrlResource(filePath.toUri());

            if (!resource.exists()) {
                return ResponseEntity.notFound().build();
            }

            // Determine the content type of the image
            String contentType = "image/jpeg"; // Adjust based on the image type, if necessary

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resource.getFilename() + "\"")
                    .body(resource);

        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @GetMapping("{petId}")
    public ResponseEntity<PetDTO> getPetById(@PathVariable("petId") Long petId) {
        PetDTO petDTO = petService.getPetById(petId);
        return ResponseEntity.ok(petDTO);
    }

    @GetMapping
    public ResponseEntity<List<PetDTO>> getAllPets() {
        List<PetDTO> pets = petService.getAllPets();
        return ResponseEntity.ok(pets);
    }

    @GetMapping("owner/{ownerId}")
    public ResponseEntity<List<PetDTO>> getPetsByOwnerId(@PathVariable("ownerId") Long ownerId) {
        List<PetDTO> pets = petService.getPetsByOwnerId(ownerId);
        return ResponseEntity.ok(pets);
    }

    @PutMapping("{petId}")
    public ResponseEntity<PetDTO> updatePet(@PathVariable("petId") Long petId,
                                            @Valid @RequestBody PetDTO updatedPet) {
        PetDTO petDTO = petService.updatePet(petId, updatedPet);
        return ResponseEntity.ok(petDTO);
    }

    @DeleteMapping("{petId}")
    public ResponseEntity<String> deletePet(@PathVariable("petId") Long petId) {
        petService.deletePet(petId);
        return ResponseEntity.ok("Pet deleted successfully.");
    }
}
