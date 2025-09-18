// src/main/java/life/work/IntFit/backend/service/FileStorageService.java
package life.work.IntFit.backend.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class FileStorageService {

    @Value("${app.uploads.root:uploads}")
    private String uploadsRoot;

    private Path checksDir;

    @PostConstruct
    public void init() throws IOException {
        Path root = Paths.get(uploadsRoot).toAbsolutePath().normalize();
        checksDir = root.resolve("checks");
        Files.createDirectories(checksDir);
    }

    public String saveCheckImage(MultipartFile file) throws IOException {
        String ext = StringUtils.getFilenameExtension(file.getOriginalFilename());
        if (ext == null || ext.isBlank()) ext = "jpg";
        String filename = "check_" + UUID.randomUUID() + "." + ext.toLowerCase();
        Path target = checksDir.resolve(filename).normalize();
        Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        return filename; // filename only
    }

    public boolean deleteCheckImageByPublicUrl(String url) {
        if (url == null) return false;
        int idx = url.indexOf("/uploads/checks/");
        if (idx == -1) return false;
        String fn = url.substring(idx + "/uploads/checks/".length());
        Path p = checksDir.resolve(fn).normalize();
        try {
            return Files.deleteIfExists(p);
        } catch (IOException e) {
            return false;
        }
    }
}
