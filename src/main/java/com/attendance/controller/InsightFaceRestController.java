package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.security.RequiresPermission;
import com.attendance.service.insightface.InsightFaceBackfillService;
import com.attendance.service.insightface.InsightFaceClient;
import com.attendance.service.insightface.InsightFaceGallery;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.attendance.security.AppModule.FACE_RECOGNITION;
import static com.attendance.security.PermissionAction.MODIFY;
import static com.attendance.security.PermissionAction.VIEW;

/**
 * Admin endpoints for the InsightFace secondary recognition layer: check its status and (re)build
 * employee embeddings from the enrolled photos on disk.
 */
@RestController
@RequestMapping("/api/insightface")
@RequiredArgsConstructor
@Slf4j
public class InsightFaceRestController {

    private final InsightFaceClient client;
    private final InsightFaceGallery gallery;
    private final InsightFaceBackfillService backfillService;

    /** Whether the feature is enabled, whether the sidecar is reachable, and the gallery size. */
    @GetMapping("/status")
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public ResponseEntity<ApiResponse<Map<String, Object>>> status() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("enabled", client.isEnabled());
        status.put("available", client.isEnabled() && client.checkHealth());
        status.put("gallerySize", gallery.size());
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    /**
     * Generate embeddings for active employees that have an enrolled photo but no embedding yet.
     * Runs in the background; returns immediately.
     */
    @PostMapping("/backfill")
    @RequiresPermission(module = FACE_RECOGNITION, action = MODIFY)
    public ResponseEntity<ApiResponse<Void>> backfill() {
        if (!client.isEnabled()) {
            return ResponseEntity.ok(ApiResponse.error("InsightFace is disabled (set insightface.enabled=true)"));
        }
        backfillService.backfillAsync();
        return ResponseEntity.ok(ApiResponse.success("Backfill started in the background", null));
    }
}
