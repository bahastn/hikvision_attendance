package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.FaceMatchRecordDTO;
import com.attendance.dto.FaceMatchSyncResult;
import com.attendance.security.AccessControlService;
import com.attendance.security.RequiresPermission;
import com.attendance.service.FaceMatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.attendance.security.AppModule.EMPLOYEES;
import static com.attendance.security.AppModule.FACE_RECOGNITION;
import static com.attendance.security.PermissionAction.*;

/**
 * REST API for face match records - download, review, and sync to database.
 */
@RestController
@RequestMapping("/api/face-matches")
@RequiredArgsConstructor
@Slf4j
public class FaceMatchRestController {

    private final FaceMatchService faceMatchService;
    private final AccessControlService access;

    /**
     * Get new/unprocessed face matches.
     */
    @GetMapping("/new")
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public ResponseEntity<ApiResponse<List<FaceMatchRecordDTO>>> getNewMatches(
            @RequestParam(required = false) Double minSimilarity) {
        List<FaceMatchRecordDTO> matches = faceMatchService.getNewMatches(
                minSimilarity != null ? minSimilarity : 80.0);
        return ResponseEntity.ok(ApiResponse.success(matches));
    }

    /**
     * Paginated, filtered match list for the Face Matches page (newest first).
     * Every filter is optional; {@code query} matches the person name or ID number.
     */
    @GetMapping
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public ResponseEntity<ApiResponse<Map<String, Object>>> searchMatches(
            @RequestParam(required = false) Long cameraId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Double minSimilarity,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "60") int size) {

        int safeSize = Math.min(Math.max(size, 1), 200);
        int safePage = Math.max(page, 0);
        Page<FaceMatchRecordDTO> result = faceMatchService.searchMatches(
                PageRequest.of(safePage, safeSize), cameraId, status, minSimilarity, query, from, to);

        Map<String, Object> body = Map.of(
                "content", result.getContent(),
                "page", result.getNumber(),
                "size", result.getSize(),
                "totalElements", result.getTotalElements(),
                "totalPages", result.getTotalPages());
        return ResponseEntity.ok(ApiResponse.success(body));
    }

    /**
     * Proxy the captured face picture of a match through the app (the raw camera URL is not
     * reachable from the browser). Cached on disk after the first fetch.
     */
    @GetMapping("/{id}/photo")
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public ResponseEntity<byte[]> matchPhoto(@PathVariable Long id) {
        return faceMatchService.getMatchPhoto(id)
                .map(bytes -> ResponseEntity.ok()
                        .contentType(MediaType.IMAGE_JPEG)
                        .cacheControl(CacheControl.maxAge(1, TimeUnit.HOURS).cachePrivate())
                        .body(bytes))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Get match statistics for dashboard.
     */
    @GetMapping("/stats")
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public ResponseEntity<ApiResponse<Map<String, Object>>> getMatchStats() {
        return ResponseEntity.ok(ApiResponse.success(faceMatchService.getMatchStatistics()));
    }

    /**
     * Download face matches as CSV.
     */
    @GetMapping("/download")
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public ResponseEntity<byte[]> downloadMatches(
            @RequestParam(required = false) Long cameraId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end,
            @RequestParam(required = false) Double minSimilarity,
            @RequestParam(defaultValue = "csv") String format) {
        
        FaceMatchSyncResult result = faceMatchService.downloadMatches(
                cameraId, start, end, minSimilarity, format);
        
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, 
                        "attachment; filename=\"" + result.getExportFilename() + "\"")
                .contentType(MediaType.parseMediaType(result.getExportContentType()))
                .body(result.getExportData());
    }

    /**
     * Sync selected matches to the employee database.
     */
    @PostMapping("/sync-to-database")
    @RequiresPermission(module = FACE_RECOGNITION, action = MODIFY)
    public ResponseEntity<ApiResponse<FaceMatchSyncResult>> syncToDatabase(
            @RequestBody SyncRequest request) {
        // Imports / links employees without a group: needs org-wide employee rights.
        access.requireGroup(EMPLOYEES, MODIFY, null);
        FaceMatchSyncResult result = faceMatchService.syncMatchesToDatabase(
                request.getMatchIds(),
                request.getAction(),
                access.currentUsername(), // never trust a client-supplied name for the audit trail
                request.getEmployeeIdMapping());
        
        return ResponseEntity.ok(ApiResponse.success(result));
    }

    /**
     * Mark matches as reviewed.
     */
    @PostMapping("/mark-reviewed")
    @RequiresPermission(module = FACE_RECOGNITION, action = MODIFY)
    public ResponseEntity<ApiResponse<Void>> markReviewed(@RequestBody List<Long> matchIds) {
        // Implementation would update status to REVIEWED
        return ResponseEntity.ok(ApiResponse.success("Marked as reviewed", null));
    }

    /**
     * Request DTO for sync operation.
     */
    @lombok.Data
    public static class SyncRequest {
        private List<Long> matchIds;
        private String action; // IMPORT, LINK, IGNORE
        /** Ignored: the signed-in user is recorded instead. */
        private String performedBy;
        private Map<Long, String> employeeIdMapping; // matchId -> employeeId for LINK action
    }
}
