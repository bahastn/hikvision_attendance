package com.attendance.service;

import com.attendance.entity.CameraConfig;
import com.attendance.repository.CameraConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Catch-up sync of attendance from the camera's face-comparison history (FCSearch). Every match
 * goes through the same pipeline as live matches ({@link MatchIngestService}): matches the live
 * poller already handled are skipped, missed ones are processed now — including the InsightFace
 * check and the WebSocket notification.
 *
 * <p>Features:
 * <ul>
 *   <li>Downloads all face-comparison matches from the camera for a date range</li>
 *   <li>Processes matches not processed yet (same pipeline as live matches)</li>
 *   <li>Skips matches already processed (durable match log)</li>
 *   <li>Can be triggered manually via REST API or runs automatically on schedule</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceSyncService {

    private final CameraConfigRepository cameraRepository;
    private final HikvisionIsapiClient isapiClient;
    private final AttendanceService attendanceService;
    private final MatchIngestService matchIngestService;

    /** Look-back of the 30-minute catch-up sync. Short on purpose: the 5-second poller handles live
     *  matches; this only catches what it missed (e.g. while the app was down). */
    @Value("${attendance.sync.auto-lookback-days:1}")
    private int defaultLookbackDays;

    @Value("${attendance.sync.auto-enabled:true}")
    private boolean autoSyncEnabled;

    @Value("${attendance.sync.auto-interval-minutes:30}")
    private int autoSyncIntervalMinutes;

    /** Overall cap on matches pulled from one camera in a single sync run (safety limit). */
    @Value("${attendance.sync.max-matches-per-camera:20000}")
    private int maxSyncMatchesPerCamera;

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    /** Result of a sync operation. */
    public record SyncResult(
            int totalFound,
            int imported,
            int skipped,
            int duplicates,
            List<String> messages
    ) {}

    /** One attendance record as reported by the camera. */
    public record CameraAttendanceRecord(
            String employeeNo,
            String employeeName,
            LocalDateTime eventTime,
            Double similarity,
            String facePicUrl,
            String cameraName
    ) {}

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Sync attendance from all active cameras for the specified date range. Not transactional on
     * purpose: each match is stored and processed in its own transaction, so one bad match cannot
     * roll back the others.
     */
    public SyncResult syncAttendanceFromCameras(LocalDate startDate, LocalDate endDate) {
        List<CameraConfig> cameras = cameraRepository.findByIsActiveTrue();
        if (cameras.isEmpty()) {
            return new SyncResult(0, 0, 0, 0, List.of("No active cameras configured"));
        }

        int totalFound = 0;
        int imported = 0;
        int skipped = 0;
        int duplicates = 0;
        List<String> messages = new ArrayList<>();

        for (CameraConfig camera : cameras) {
            try {
                SyncResult result = syncAttendanceFromCamera(camera, startDate, endDate);
                totalFound += result.totalFound();
                imported += result.imported();
                skipped += result.skipped();
                duplicates += result.duplicates();
                messages.addAll(result.messages());
            } catch (Exception e) {
                log.error("Failed to sync attendance from camera {}: {}", camera.getCameraName(), e.getMessage(), e);
                messages.add("Camera " + camera.getCameraName() + ": sync failed - " + e.getMessage());
            }
        }

        log.info("Attendance sync completed: {} found, {} processed, {} failed, {} already processed",
                totalFound, imported, skipped, duplicates);
        return new SyncResult(totalFound, imported, skipped, duplicates, messages);
    }

    /** Sync attendance from a single camera for the specified date range. */
    public SyncResult syncAttendanceFromCamera(Long cameraId, LocalDate startDate, LocalDate endDate) {
        CameraConfig camera = cameraRepository.findById(cameraId)
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .orElseThrow(() -> new RuntimeException("Camera not found or not active"));
        return syncAttendanceFromCamera(camera, startDate, endDate);
    }

    /**
     * Sync attendance from a single camera for the specified date range. The counts map to the
     * shared pipeline: {@code imported} = processed now, {@code duplicates} = already processed
     * before, {@code skipped} = failed (retried on the next run).
     */
    public SyncResult syncAttendanceFromCamera(CameraConfig camera, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay().minusSeconds(1);

        log.info("Syncing attendance from camera {} for {} to {}", camera.getCameraName(), start, end);

        List<HikvisionIsapiClient.FaceComparison> matches = fetchAllMatches(camera, start, end);
        if (matches.isEmpty()) {
            return new SyncResult(0, 0, 0, 0, List.of("No matches found on camera " + camera.getCameraName()));
        }

        MatchIngestService.Result result = matchIngestService.ingest(camera, matches);
        if (result.processed() > 0) {
            log.info("Catch-up sync on {} processed {} match(es) the live poller had missed",
                    camera.getCameraName(), result.processed());
        }
        return new SyncResult(matches.size(), result.processed(), result.failed(), result.alreadyDone(),
                result.messages());
    }

    /**
     * Get all attendance records from a camera without importing them.
     * Useful for previewing what would be synced.
     */
    public List<CameraAttendanceRecord> getCameraAttendance(Long cameraId, LocalDate startDate, LocalDate endDate) {
        CameraConfig camera = cameraRepository.findById(cameraId)
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .orElseThrow(() -> new RuntimeException("Camera not found or not active"));

        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay().minusSeconds(1);

        List<HikvisionIsapiClient.FaceComparison> matches = fetchAllMatches(camera, start, end);

        return matches.stream()
                .map(m -> new CameraAttendanceRecord(
                        m.certificateNumber,
                        m.name,
                        LocalDateTime.ofInstant(Instant.ofEpochMilli(m.snapTimeMillis), ZoneId.systemDefault()),
                        m.similarity,
                        m.facePicUrl,
                        camera.getCameraName()
                ))
                .sorted(Comparator.comparing(CameraAttendanceRecord::eventTime))
                .collect(Collectors.toList());
    }

    /**
     * Automatic catch-up sync (every 30 minutes by default) for matches the live poller missed,
     * e.g. while the application was stopped.
     */
    @Scheduled(fixedDelayString = "${attendance.sync.auto-interval-ms:1800000}", initialDelay = 60000)
    public void autoSync() {
        if (!autoSyncEnabled) {
            return;
        }

        log.debug("Running automatic attendance catch-up sync...");
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(Math.max(0, defaultLookbackDays));

        try {
            SyncResult result = syncAttendanceFromCameras(startDate, today);
            if (result.imported() > 0) {
                log.info("Catch-up sync processed {} missed match(es)", result.imported());
                attendanceService.broadcastStats();
            }
        } catch (Exception e) {
            log.error("Auto-sync failed: {}", e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Fetch all face-comparison matches from the camera for the given time range. The ISAPI client
     * pages through the whole result set (advancing searchResultPosition while the camera reports
     * MORE), so a single call returns every match up to the overall cap.
     */
    private List<HikvisionIsapiClient.FaceComparison> fetchAllMatches(
            CameraConfig camera, LocalDateTime start, LocalDateTime end) {
        List<HikvisionIsapiClient.FaceComparison> allMatches = new ArrayList<>(
                isapiClient.searchFaceComparisons(camera, start, end, maxSyncMatchesPerCamera));
        allMatches.sort(Comparator.comparingLong(m -> m.snapTimeMillis));
        return allMatches;
    }
}
