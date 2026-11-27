package com.attendance.controller;

import com.attendance.security.RequiresPermission;
import static com.attendance.security.AppModule.FACE_RECOGNITION;
import static com.attendance.security.PermissionAction.*;

import com.attendance.dto.ApiResponse;
import com.attendance.service.FaceMatchService;
import com.attendance.service.ScheduledSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Dashboard widgets API for sync status and statistics.
 */
@RestController
@RequestMapping("/api/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardWidgetController {

    private final FaceMatchService faceMatchService;
    private final ScheduledSyncService scheduledSyncService;

    /**
     * Get sync status overview for dashboard.
     */
    @GetMapping("/sync-status")
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSyncStatus() {
        Map<String, Object> status = new HashMap<>();
        
        // Face match statistics
        Map<String, Object> matchStats = faceMatchService.getMatchStatistics();
        status.put("faceMatches", matchStats);
        
        // Recent sync batches
        status.put("recentBatches", scheduledSyncService.getRecentSyncBatches(7));
        
        // Pending sync count (employees with PENDING status)
        // This would come from EmployeeRepository in a real implementation
        status.put("pendingSyncCount", 0);
        
        return ResponseEntity.ok(ApiResponse.success(status));
    }

    /**
     * Get quick stats for dashboard widgets.
     */
    @GetMapping("/quick-stats")
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public ResponseEntity<ApiResponse<Map<String, Object>>> getQuickStats() {
        Map<String, Object> stats = new HashMap<>();
        
        // New face matches needing review
        Map<String, Object> matchStats = faceMatchService.getMatchStatistics();
        stats.put("newMatches", matchStats.get("totalNew"));
        stats.put("last24Hours", matchStats.get("last24Hours"));
        
        // Sync health
        stats.put("syncHealth", "good"); // Would be calculated based on recent sync results
        
        return ResponseEntity.ok(ApiResponse.success(stats));
    }
}
