package com.attendance.service;

import com.attendance.entity.CameraConfig;
import com.attendance.entity.SyncHistory;
import com.attendance.repository.CameraConfigRepository;
import com.attendance.repository.SyncHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Scheduled service for automatic camera synchronization.
 * Runs nightly syncs and generates reports.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledSyncService {

    private final EnhancedCameraFaceLibraryService syncService;
    private final CameraConfigRepository cameraRepository;
    private final SyncHistoryRepository syncHistoryRepository;

    @Value("${attendance.sync.auto-enabled:true}")
    private boolean autoSyncEnabled;

    @Value("${attendance.sync.auto-interval-minutes:30}")
    private int autoSyncIntervalMinutes;

    /**
     * Nightly full sync at 2 AM.
     */
    @Scheduled(cron = "${attendance.sync.nightly-cron:0 0 2 * * ?}")
    @Transactional
    public void nightlySync() {
        if (!autoSyncEnabled) {
            log.info("Auto-sync is disabled, skipping nightly sync");
            return;
        }

        log.info("Starting nightly camera face library sync");
        String batchId = "NIGHTLY-" + UUID.randomUUID().toString();
        
        try {
            Map<String, EnhancedCameraFaceLibraryService.SyncResult> results = 
                    syncService.syncAllCameras("scheduled-nightly");
            
            int totalImported = results.values().stream().mapToInt(EnhancedCameraFaceLibraryService.SyncResult::imported).sum();
            int totalLinked = results.values().stream().mapToInt(EnhancedCameraFaceLibraryService.SyncResult::linked).sum();
            int totalRemoved = results.values().stream().mapToInt(EnhancedCameraFaceLibraryService.SyncResult::removed).sum();
            
            log.info("Nightly sync completed: {} imported, {} linked, {} removed across {} cameras",
                    totalImported, totalLinked, totalRemoved, results.size());
            
            // Generate summary report
            generateSyncReport(batchId, results);
            
        } catch (Exception e) {
            log.error("Nightly sync failed", e);
        }
    }

    /**
     * Periodic quick sync (configurable interval).
     */
    @Scheduled(fixedDelayString = "${attendance.sync.quick-interval-ms:1800000}") // Default 30 min
    public void quickSync() {
        if (!autoSyncEnabled) {
            return;
        }

        log.debug("Starting quick sync check");
        
        // Only sync cameras that haven't been synced recently
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(autoSyncIntervalMinutes);
        List<CameraConfig> cameras = cameraRepository.findByIsActiveTrue();
        
        for (CameraConfig camera : cameras) {
            if (camera.getLastSync() == null || camera.getLastSync().isBefore(threshold)) {
                try {
                    log.info("Quick syncing camera: {}", camera.getCameraName());
                    syncService.syncCameraFaceLibrary(camera.getId(), "scheduled-quick");
                } catch (Exception e) {
                    log.warn("Quick sync failed for camera {}: {}", camera.getCameraName(), e.getMessage());
                }
            }
        }
    }

    /**
     * Generate a sync report for auditing.
     */
    private void generateSyncReport(String batchId, Map<String, EnhancedCameraFaceLibraryService.SyncResult> results) {
        // In a real implementation, this could:
        // 1. Save report to database
        // 2. Send email notification
        // 3. Write to log file
        // 4. Create dashboard notification
        
        log.info("=== Sync Report [{}] ===", batchId);
        results.forEach((cameraName, result) -> {
            log.info("Camera: {} | Imported: {} | Linked: {} | Removed: {} | Messages: {}",
                    cameraName, result.imported(), result.linked(), result.removed(), 
                    result.messages().size());
        });
        log.info("=== End Report ===");
    }

    /**
     * Get sync history for a camera.
     */
    @Transactional(readOnly = true)
    public List<SyncHistory> getCameraSyncHistory(Long cameraId, int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return syncHistoryRepository.findByCameraIdAndSyncTimeBetweenOrderBySyncTimeDesc(
                cameraId, since, LocalDateTime.now());
    }

    /**
     * Get recent sync batches.
     */
    @Transactional(readOnly = true)
    public List<String> getRecentSyncBatches(int days) {
        LocalDateTime since = LocalDateTime.now().minusDays(days);
        return syncHistoryRepository.findRecentBatchIds(since);
    }
}
