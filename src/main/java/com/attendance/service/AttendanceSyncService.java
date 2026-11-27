package com.attendance.service;

import com.attendance.dto.FaceEvent;
import com.attendance.entity.AttendanceRecord;
import com.attendance.entity.CameraConfig;
import com.attendance.entity.Employee;
import com.attendance.repository.AttendanceRecordRepository;
import com.attendance.repository.CameraConfigRepository;
import com.attendance.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Synchronises attendance records from the camera's face-comparison history
 * (FCSearch) with the application database. This ensures no attendance is
 * missed even when the WebSocket connection drops.
 *
 * <p>Features:
 * <ul>
 *   <li>Downloads all face-comparison matches from the camera for a date range</li>
 *   <li>Creates missing attendance records in the database</li>
 *   <li>Skips duplicates (same employee, same day, similar time)</li>
 *   <li>Can be triggered manually via REST API or runs automatically on schedule</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AttendanceSyncService {

    private final CameraConfigRepository cameraRepository;
    private final EmployeeRepository employeeRepository;
    private final AttendanceRecordRepository attendanceRepository;
    private final HikvisionIsapiClient isapiClient;
    private final AttendanceService attendanceService;

    @Value("${attendance.sync.lookback-days:7}")
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
     * Sync attendance records from all active cameras for the specified date range.
     * This downloads all face-comparison matches from each camera and creates
     * missing attendance records in the database.
     */
    @Transactional
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

        log.info("Attendance sync completed: {} found, {} imported, {} skipped, {} duplicates",
                totalFound, imported, skipped, duplicates);
        return new SyncResult(totalFound, imported, skipped, duplicates, messages);
    }

    /**
     * Sync attendance records from a single camera for the specified date range.
     */
    @Transactional
    public SyncResult syncAttendanceFromCamera(Long cameraId, LocalDate startDate, LocalDate endDate) {
        CameraConfig camera = cameraRepository.findById(cameraId)
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .orElseThrow(() -> new RuntimeException("Camera not found or not active"));
        return syncAttendanceFromCamera(camera, startDate, endDate);
    }

    /**
     * Sync attendance records from a single camera for the specified date range.
     */
    @Transactional
    public SyncResult syncAttendanceFromCamera(CameraConfig camera, LocalDate startDate, LocalDate endDate) {
        LocalDateTime start = startDate.atStartOfDay();
        LocalDateTime end = endDate.plusDays(1).atStartOfDay().minusSeconds(1);

        log.info("Syncing attendance from camera {} for {} to {}", camera.getCameraName(), start, end);

        // Fetch all face-comparison matches from the camera
        List<HikvisionIsapiClient.FaceComparison> matches = fetchAllMatches(camera, start, end);
        if (matches.isEmpty()) {
            return new SyncResult(0, 0, 0, 0, List.of("No matches found on camera " + camera.getCameraName()));
        }

        // Load all employees for quick lookup
        Map<String, Employee> byEmployeeId = employeeRepository.findAll().stream()
                .collect(Collectors.toMap(Employee::getEmployeeId, e -> e, (a, b) -> a));
        Map<String, Employee> byFaceTemplateId = employeeRepository.findAll().stream()
                .filter(e -> e.getFaceTemplateId() != null && !e.getFaceTemplateId().isBlank())
                .collect(Collectors.toMap(Employee::getFaceTemplateId, e -> e, (a, b) -> a));

        int imported = 0;
        int skipped = 0;
        int duplicates = 0;
        List<String> messages = new ArrayList<>();

        for (HikvisionIsapiClient.FaceComparison match : matches) {
            try {
                // Resolve employee
                Employee employee = resolveEmployee(match, byEmployeeId, byFaceTemplateId);
                if (employee == null) {
                    skipped++;
                    messages.add("Skipped: unknown person " + match.name + " (" + match.certificateNumber + ")");
                    continue;
                }

                // Check for duplicate (same employee, same day, similar time)
                LocalDateTime eventTime = LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(match.snapTimeMillis), ZoneId.systemDefault());
                LocalDate eventDate = eventTime.toLocalDate();

                if (isDuplicate(employee.getId(), eventDate, eventTime)) {
                    duplicates++;
                    continue;
                }

                // Create attendance record
                createAttendanceRecord(employee, eventTime, match.similarity, camera);
                imported++;
                messages.add("Imported: " + employee.getFullName() + " at " + eventTime);

            } catch (Exception e) {
                skipped++;
                messages.add("Error processing " + match.name + ": " + e.getMessage());
                log.warn("Failed to process match {}: {}", match.name, e.getMessage());
            }
        }

        // Update camera last sync time
        camera.setLastSync(LocalDateTime.now());
        cameraRepository.save(camera);

        return new SyncResult(matches.size(), imported, skipped, duplicates, messages);
    }

    /**
     * Get all attendance records from a camera without importing them.
     * Useful for previewing what would be synced.
     */
    @Transactional(readOnly = true)
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
     * Automatic scheduled sync. Runs every 30 minutes by default to catch any
     * attendance records that were missed due to WebSocket disconnections.
     */
    @Scheduled(fixedDelayString = "${attendance.sync.auto-interval-ms:1800000}", initialDelay = 60000)
    public void autoSync() {
        if (!autoSyncEnabled) {
            return;
        }

        log.debug("Running automatic attendance sync...");
        LocalDate today = LocalDate.now();
        LocalDate startDate = today.minusDays(defaultLookbackDays);

        try {
            SyncResult result = syncAttendanceFromCameras(startDate, today);
            if (result.imported() > 0) {
                log.info("Auto-sync imported {} attendance records", result.imported());
                // Broadcast stats update via WebSocket
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
     * Fetch all face-comparison matches from the camera for the given time range.
     * The ISAPI client pages through the whole result set internally (advancing
     * searchResultPosition while the camera reports MORE), so a single call returns every match
     * up to the overall cap.
     */
    private List<HikvisionIsapiClient.FaceComparison> fetchAllMatches(
            CameraConfig camera, LocalDateTime start, LocalDateTime end) {

        List<HikvisionIsapiClient.FaceComparison> allMatches = new ArrayList<>(
                isapiClient.searchFaceComparisons(camera, start, end, maxSyncMatchesPerCamera));

        // Sort by time
        allMatches.sort(Comparator.comparingLong(m -> m.snapTimeMillis));
        return allMatches;
    }

    /**
     * Resolve an employee from a face-comparison match.
     */
    private Employee resolveEmployee(
            HikvisionIsapiClient.FaceComparison match,
            Map<String, Employee> byEmployeeId,
            Map<String, Employee> byFaceTemplateId) {

        // Try certificate number (employee ID) first
        if (match.certificateNumber != null && !match.certificateNumber.isBlank()) {
            Employee emp = byEmployeeId.get(match.certificateNumber.trim());
            if (emp != null) return emp;
        }

        // Try PID (face template ID)
        if (match.pid != null && !match.pid.isBlank()) {
            Employee emp = byFaceTemplateId.get(match.pid.trim());
            if (emp != null) return emp;
        }

        // Try by name
        if (match.name != null && !match.name.isBlank()) {
            String name = match.name.trim();
            for (Employee emp : byEmployeeId.values()) {
                if (name.equalsIgnoreCase(emp.getFullName()) ||
                    (emp.getFullNameKurdish() != null && name.equalsIgnoreCase(emp.getFullNameKurdish()))) {
                    return emp;
                }
            }
        }

        return null;
    }

    /**
     * Check if an attendance record already exists for this employee on this date
     * with a similar check-in time (within 5 minutes).
     */
    private boolean isDuplicate(Long employeeId, LocalDate date, LocalDateTime eventTime) {
        Optional<AttendanceRecord> existing = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employeeId, date);

        if (existing.isEmpty()) {
            return false;
        }

        AttendanceRecord record = existing.get();
        if (record.getCheckIn() == null) {
            return false;
        }

        // Consider it a duplicate if the existing check-in is within 5 minutes
        long minutesDiff = Math.abs(java.time.Duration.between(record.getCheckIn(), eventTime).toMinutes());
        return minutesDiff < 5;
    }

    /**
     * Create an attendance record from a camera match.
     */
    private void createAttendanceRecord(Employee employee, LocalDateTime eventTime,
                                        Double similarity, CameraConfig camera) {
        LocalDate eventDate = eventTime.toLocalDate();

        // Check if there's already a record for this employee on this date
        Optional<AttendanceRecord> existing = attendanceRepository
                .findByEmployeeIdAndAttendanceDate(employee.getId(), eventDate);

        if (existing.isPresent()) {
            AttendanceRecord record = existing.get();
            // If no check-out yet, this might be the check-out
            if (record.getCheckOut() == null && eventTime.isAfter(record.getCheckIn())) {
                record.setCheckOut(eventTime);
                record.setWorkHours(calculateWorkHours(record.getCheckIn(), eventTime));
                attendanceRepository.save(record);
                log.debug("Updated check-out for {} on {}", employee.getEmployeeId(), eventDate);
            }
            return;
        }

        // Create new record with check-in
        AttendanceRecord record = AttendanceRecord.builder()
                .employee(employee)
                .attendanceDate(eventDate)
                .checkIn(eventTime)
                .status(determineStatus(employee, eventTime))
                .deviceId(camera.getCameraName())
                .similarityScore(similarity)
                .direction("IN")
                .build();

        attendanceRepository.save(record);
        log.debug("Created attendance record for {} on {}", employee.getEmployeeId(), eventDate);
    }

    private Double calculateWorkHours(LocalDateTime checkIn, LocalDateTime checkOut) {
        if (checkIn == null || checkOut == null) return 0.0;
        long minutes = java.time.Duration.between(checkIn, checkOut).toMinutes();
        return Math.round(minutes / 60.0 * 100.0) / 100.0;
    }

    private AttendanceRecord.AttendanceStatus determineStatus(Employee employee, LocalDateTime checkIn) {
        // Simple status determination - can be enhanced with shift rules
        LocalTime checkInTime = checkIn.toLocalTime();
        LocalTime workStart = LocalTime.of(8, 0); // Default 8 AM
        int lateThreshold = 15; // Default 15 minutes

        return checkInTime.isAfter(workStart.plusMinutes(lateThreshold))
                ? AttendanceRecord.AttendanceStatus.LATE
                : AttendanceRecord.AttendanceStatus.PRESENT;
    }
}
