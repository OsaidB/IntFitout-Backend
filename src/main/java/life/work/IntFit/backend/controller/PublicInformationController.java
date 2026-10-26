package life.work.IntFit.backend.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
public class PublicInformationController {

    private static final MediaType HTML_UTF8 = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    @GetMapping(value = "/about", produces = "text/html;charset=UTF-8")
    public ResponseEntity<Resource> about() {
        return html("static/about.html");
    }

    @GetMapping(value = "/privacy", produces = "text/html;charset=UTF-8")
    public ResponseEntity<Resource> privacy() {
        return html("static/privacy.html");
    }

    @GetMapping(value = "/data-deletion", produces = "text/html;charset=UTF-8")
    public ResponseEntity<Resource> dataDeletion() {
        return html("static/data-deletion.html");
    }

    private ResponseEntity<Resource> html(String path) {
        return ResponseEntity.ok()
                .contentType(HTML_UTF8)
                .body(new ClassPathResource(path));
    }
}
