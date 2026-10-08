package com.attendance.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Serves face images captured from cameras that are stored on disk.
 * Images are written under {@code attendance.face-storage-dir} and exposed
 * over HTTP at {@code /faces/**}.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${attendance.face-storage-dir:faces}")
    private String faceStorageDir;

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Paths.get(faceStorageDir).toAbsolutePath().normalize()
                .toUri().toString();
        registry.addResourceHandler("/faces/**")
                .addResourceLocations(location);
    }
}
