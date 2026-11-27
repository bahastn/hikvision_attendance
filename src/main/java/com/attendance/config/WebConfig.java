package com.attendance.config;

import com.attendance.security.PermissionInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Installs the permission check that runs before every controller method.
 * <p>
 * Face images are NOT served as static resources (they are personal biometric
 * data): {@code FaceImageController} serves {@code /faces/**} with access control,
 * so that path is intentionally not excluded from the interceptor here.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;

    public WebConfig(PermissionInterceptor permissionInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor)
                .excludePathPatterns("/css/**", "/js/**", "/images/**", "/fonts/**", "/favicon.ico");
    }
}
