// src/main/java/life/work/IntFit/backend/controller/InvoiceReceivingController.java
package life.work.IntFit.backend.controller;

import life.work.IntFit.backend.dto.InvoiceReceivingDTO;
import life.work.IntFit.backend.service.InvoiceReceivingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
@CrossOrigin("*")
public class InvoiceReceivingController {

    private final InvoiceReceivingService service;

    @GetMapping("/{id}/receiving-json")
    public InvoiceReceivingDTO receivingJson(@PathVariable Long id,
                                             @RequestParam(defaultValue = "ar") String lang) {
        return service.loadSanitized(id);
    }

    @GetMapping("/{id}/receiving-images")
    public Map<String, Object> receivingImages(@PathVariable Long id,
                                               @RequestParam(defaultValue = "ar") String lang) {
        var dto = service.loadSanitized(id);
        var imgs = service.renderImages(dto, lang);
        String base = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/invoices/{id}/receiving-images/")
                .buildAndExpand(id)
                .toUriString();

        List<String> urls = new ArrayList<>();
        for (int p = 1; p <= imgs.size(); p++) urls.add(base + p + "?lang=" + lang);

        return Map.of("images", urls, "pages", imgs.size());
    }

    @GetMapping(value = "/{id}/receiving-images/{page}", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> receivingImagePage(@PathVariable Long id,
                                                     @PathVariable int page,
                                                     @RequestParam(defaultValue = "ar") String lang) {
        var dto = service.loadSanitized(id);
        var imgs = service.renderImages(dto, lang);
        if (page < 1 || page > imgs.size()) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        BufferedImage img = imgs.get(page - 1);
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", baos);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_PNG)
                    .cacheControl(CacheControl.noCache())
                    .body(baos.toByteArray());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
