package com.attendance.controller;

import com.attendance.security.RequiresPermission;
import static com.attendance.security.AppModule.ATTENDANCE_SYNC;
import static com.attendance.security.PermissionAction.*;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.SyncHistoryDTO;
import com.attendance.entity.SyncHistory;
import com.attendance.service.ScheduledSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * REST API for sync history and audit trail.
 */
@RestController
@RequestMapping("/api/sync-history")
@RequiredArgsConstructor
@Slf4j
public class SyncHistoryController {

    private final ScheduledSyncService scheduledSyncService;

    /**
     * Get sync history for a specific camera.
     */
    @GetMapping("/camera/{cameraId}")
    @RequiresPermission(module = ATTENDANCE_SYNC, action = VIEW)
    public ResponseEntity<ApiResponse<List<SyncHistoryDTO>>> getCameraHistory(
            @PathVariable Long cameraId,
            @RequestParam(defaultValue = "7") int days) {
        
        List<SyncHistory> history = scheduledSyncService.getCameraSyncHistory(cameraId, days);
        List<SyncHistoryDTO> dtos = history.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        
        return ResponseEntity.ok(ApiResponse.success(dtos));
    }

    /**
     * Get recent sync batches.
     */
    @GetMapping("/batches")
    @RequiresPermission(module = ATTENDANCE_SYNC, action = VIEW)
    public ResponseEntity<ApiResponse<List<String>>> getRecentBatches(
            @RequestParam(defaultValue = "7") int days) {
        return ResponseEntity.ok(ApiResponse.success(
                scheduledSyncService.getRecentSyncBatches(days)));
    }

    /**
     * Get sync history for a specific batch.
     */
    @GetMapping("/batch/{batchId}")
    @RequiresPermission(module = ATTENDANCE_SYNC, action = VIEW)
    public ResponseEntity<ApiResponse<List<SyncHistoryDTO>>> getBatchHistory(
            @PathVariable String batchId) {
        // This would use the repository to find by batch ID
        // For now, return empty list
        return ResponseEntity.ok(ApiResponse.success(List.of()));
    }

    private SyncHistoryDTO toDTO(SyncHistory history) {
        return SyncHistoryDTO.builder()
                .id(history.getId())
                .syncTime(history.getSyncTime())
                .cameraId(history.getCamera() != null ? history.getCamera().getId() : null)
                .cameraName(history.getCamera() != null ? history.getCamera().getCameraName() : null)
                .operation(history.getOperation() != null ? history.getOperation().name() : null)
                .employeeId(history.getEmployeeId())
                .employeeNumber(history.getEmployeeNumber())
                .cameraPid(history.getCameraPid())
                .previousValue(history.getPreviousValue())
                .newValue(history.getNewValue())
                .performedBy(history.getPerformedBy())
                .status(history.getStatus() != null ? history.getStatus().name() : null)
                .errorMessage(history.getErrorMessage())
                .syncBatchId(history.getSyncBatchId())
                .build();
    }
}
