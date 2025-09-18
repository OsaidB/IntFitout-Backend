// src/main/java/life/work/IntFit/backend/config/StaticResourceConfig.java
package life.work.IntFit.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

import java.nio.file.Path;
import java.nio.file.Paths;

@Configuration
public class StaticResourceConfig implements WebMvcConfigurer {

    @Value("${app.uploads.root:uploads}")
    private String uploadsRoot;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path root = Paths.get(uploadsRoot).toAbsolutePath().normalize();
        String location = "file:" + root.toString() + "/";
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location)
                .setCachePeriod(3600);
    }
}
