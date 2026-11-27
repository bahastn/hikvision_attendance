package com.attendance.controller;

import com.attendance.security.AccessControlService;
import com.attendance.security.AppModule;
import com.attendance.security.AuthenticatedOnly;
import com.attendance.security.PermissionAction;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Serves captured and enrolled face images from disk, with access control.
 * <p>
 * This replaces the old open static resource handler: images are personal
 * biometric data, so a signed-in user may only see an employee's enrolled photo
 * when they can view that employee (or hold face-recognition rights), and camera
 * capture/detection images are gated behind the relevant module permission.
 * The URL space ({@code /faces/...}) is unchanged, so stored URLs keep working.
 */
@Controller
@Slf4j
public class FaceImageController {

    private static final Pattern ENROLLED_EMPLOYEE = Pattern.compile("^enrolled/employee_(\\d+)\\.[A-Za-z0-9]+$");

    @Value("${attendance.face-storage-dir:faces}")
    private String faceStorageDir;

    private final AccessControlService access;

    public FaceImageController(AccessControlService access) {
        this.access = access;
    }

    @GetMapping("/faces/**")
    @ResponseBody
    @AuthenticatedOnly // real authorization is decided per image below
    public ResponseEntity<byte[]> serve(HttpServletRequest request) throws IOException {
        String fullPath = (String) request.getAttribute(HandlerMapping.PATH_WITHIN_HANDLER_MAPPING_ATTRIBUTE);
        String relative = fullPath.startsWith("/faces/") ? fullPath.substring("/faces/".length()) : "";
        Path base = Paths.get(faceStorageDir).toAbsolutePath().normalize();
        Path file = base.resolve(relative).normalize();
        if (relative.isBlank() || !file.startsWith(base) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }

        authorize(relative);

        byte[] bytes = Files.readAllBytes(file);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(contentType(relative))
                .body(bytes);
    }

    /**
     * Decide who may see this image:
     * <ul>
     *   <li>an employee's enrolled photo: anyone who may view that employee (their group),
     *       or anyone with face-recognition view;</li>
     *   <li>any other capture / detection / imported image: face-recognition view
     *       (these come from cameras and are not tied to a single employee group).</li>
     * </ul>
     */
    private void authorize(String relative) {
        Matcher m = ENROLLED_EMPLOYEE.matcher(relative);
        if (m.matches()) {
            Long employeeId = Long.valueOf(m.group(1));
            if (access.has(AppModule.FACE_RECOGNITION, PermissionAction.VIEW)) {
                return;
            }
            access.requireEmployee(AppModule.EMPLOYEES, PermissionAction.VIEW, employeeId);
            return;
        }
        if (!access.has(AppModule.FACE_RECOGNITION, PermissionAction.VIEW)
                && !access.has(AppModule.ATTENDANCE, PermissionAction.VIEW)) {
            throw access.denied(AppModule.FACE_RECOGNITION, PermissionAction.VIEW, false);
        }
    }

    private static MediaType contentType(String relative) {
        String lower = relative.toLowerCase();
        if (lower.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (lower.endsWith(".gif")) return MediaType.IMAGE_GIF;
        return MediaType.IMAGE_JPEG;
    }
}
