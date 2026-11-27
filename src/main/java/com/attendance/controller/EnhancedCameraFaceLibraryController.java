package com.attendance.controller;

import com.attendance.security.RequiresPermission;
import static com.attendance.security.AppModule.EMPLOYEES;
import static com.attendance.security.AppModule.FACE_RECOGNITION;
import static com.attendance.security.PermissionAction.*;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.ImportPreview;
import com.attendance.service.EnhancedCameraFaceLibraryService;
import com.attendance.service.FaceLibrarySyncJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Enhanced REST API for camera face library synchronization with preview and selective sync.
 */
@RestController
@RequestMapping("/api/camera-face-library")
@RequiredArgsConstructor
@Slf4j
public class EnhancedCameraFaceLibraryController {

    private final EnhancedCameraFaceLibraryService enhancedService;
    private final com.attendance.service.FaceLibrarySyncJobService syncJobService;
    private final com.attendance.security.AccessControlService access;

    /**
     * Preview what will happen during sync without making changes.
     */
    @GetMapping("/{cameraId}/preview")
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public ResponseEntity<ApiResponse<ImportPreview>> previewSync(@PathVariable Long cameraId) {
        try {
            ImportPreview preview = enhancedService.previewSync(cameraId);
            return ResponseEntity.ok(ApiResponse.success(preview));
        } catch (Exception e) {
            log.error("Preview failed for camera {}", cameraId, e);
            return ResponseEntity.ok(ApiResponse.error("Preview failed: " + e.getMessage()));
        }
    }

    /**
     * Start a selective sync (selected people only) in the background. Returns a job id to poll.
     */
    @PostMapping("/{cameraId}/selective-sync")
    @RequiresPermission(module = FACE_RECOGNITION, action = MODIFY)
    public ResponseEntity<ApiResponse<Map<String, String>>> selectiveSync(
            @PathVariable Long cameraId,
            @RequestBody SelectiveSyncRequest request) {
        access.requireGroup(EMPLOYEES, MODIFY, null);
        String jobId = syncJobService.startSelectiveSync(
                cameraId,
                request.getSelectedPids(),
                request.getPidToEmployeeIdMap(),
                access.currentUsername());
        return ResponseEntity.ok(ApiResponse.success(Map.of("jobId", jobId)));
    }

    /**
     * Start a full sync (with audit trail) in the background. Returns a job id to poll.
     */
    @PostMapping("/{cameraId}/sync")
    @RequiresPermission(module = FACE_RECOGNITION, action = MODIFY)
    public ResponseEntity<ApiResponse<Map<String, String>>> fullSync(
            @PathVariable Long cameraId,
            @RequestParam(required = false) String performedBy) {
        access.requireGroup(EMPLOYEES, MODIFY, null);
        String jobId = syncJobService.startCameraSync(cameraId, access.currentUsername());
        return ResponseEntity.ok(ApiResponse.success(Map.of("jobId", jobId)));
    }

    /**
     * Start a sync of all active cameras in the background. Returns a job id to poll.
     */
    @PostMapping("/sync-all")
    @RequiresPermission(module = FACE_RECOGNITION, action = MODIFY)
    public ResponseEntity<ApiResponse<Map<String, String>>> syncAll(
            @RequestParam(required = false) String performedBy) {
        access.requireGroup(EMPLOYEES, MODIFY, null);
        String jobId = syncJobService.startSyncAll(access.currentUsername());
        return ResponseEntity.ok(ApiResponse.success(Map.of("jobId", jobId)));
    }

    /**
     * Poll the status (and, when finished, the counts) of a background sync job.
     */
    @GetMapping("/jobs/{jobId}")
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public ResponseEntity<ApiResponse<FaceLibrarySyncJobService.JobStatus>> jobStatus(@PathVariable String jobId) {
        FaceLibrarySyncJobService.JobStatus status = syncJobService.getStatus(jobId);
        if (status == null) {
            return ResponseEntity.ok(ApiResponse.error("Job not found or expired"));
        }
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    /**
     * Resolve a conflict between camera and database.
     */
    @PostMapping("/{cameraId}/resolve-conflict")
    @RequiresPermission(module = FACE_RECOGNITION, action = MODIFY)
    public ResponseEntity<ApiResponse<Void>> resolveConflict(
            @PathVariable Long cameraId,
            @RequestBody ConflictResolutionRequest request) {
        access.requireGroup(EMPLOYEES, MODIFY, null);
        try {
            enhancedService.resolveConflict(
                    cameraId,
                    request.getPid(),
                    request.getResolution(),
                    access.currentUsername());
            return ResponseEntity.ok(ApiResponse.success("Conflict resolved", null));
        } catch (Exception e) {
            log.error("Conflict resolution failed for camera {}", cameraId, e);
            return ResponseEntity.ok(ApiResponse.error("Resolution failed: " + e.getMessage()));
        }
    }

    @lombok.Data
    public static class SelectiveSyncRequest {
        private List<String> selectedPids;
        private Map<String, String> pidToEmployeeIdMap;
        private String performedBy;
    }

    @lombok.Data
    public static class ConflictResolutionRequest {
        private String pid;
        private String resolution; // USE_CAMERA, USE_DATABASE, MERGE
        private String performedBy;
    }
}
