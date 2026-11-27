package com.attendance.service;

import com.attendance.dto.FaceMatchRecordDTO;
import com.attendance.dto.FaceMatchSyncResult;
import com.attendance.entity.*;
import com.attendance.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Manages face match records from cameras - recording, reviewing, downloading,
 * and syncing matched persons to the employee database.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaceMatchService {

    private final FaceMatchRecordRepository matchRepository;
    private final EmployeeRepository employeeRepository;
    private final CameraConfigRepository cameraRepository;
    private final EmployeeCameraTargetRepository targetRepository;
    private final SyncHistoryRepository syncHistoryRepository;
    private final HikvisionIsapiClient isapiClient;
    private final FaceEnrollmentService enrollmentService;

    @Value("${attendance.face-storage-dir:faces}")
    private String faceStorageDir;

    private static final DateTimeFormatter MATCH_KEY_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    /**
     * Record a new raw face match from a camera. Idempotent: a {@code matchKey} of
     * {@code cameraId|certificateNumber-or-pid|second} is unique, so re-polling an overlapping
     * window (or a restart replaying the gap) never stores the same match twice. New matches are
     * queued for the central-server push ({@link FaceMatchRecord.CentralSyncState#PENDING}).
     *
     * <p>Deliberately NOT {@code @Transactional}: the pre-check, the insert, and the
     * race-fallback lookup each run in their own transaction, so a lost unique-key race does not
     * leave a rollback-only transaction that the caller's loop would trip over.
     */
    public FaceMatchRecord recordMatch(CameraConfig camera, String pid, String certificateNumber,
                                       String name, Double similarity, LocalDateTime matchTime,
                                       String facePicUrl, String snapPicUrl) {
        String matchKey = buildMatchKey(camera, pid, certificateNumber, matchTime);

        Optional<FaceMatchRecord> existing = matchRepository.findByMatchKey(matchKey);
        if (existing.isPresent()) {
            log.debug("Skipping duplicate match {} on camera {}", matchKey, camera.getId());
            return existing.get();
        }

        FaceMatchRecord record = FaceMatchRecord.builder()
                .matchKey(matchKey)
                .cameraPid(pid)
                .certificateNumber(certificateNumber)
                .personName(name)
                .similarity(similarity)
                .matchTime(matchTime)
                .camera(camera)
                .facePicUrl(facePicUrl)
                .snapPicUrl(snapPicUrl)
                .status(FaceMatchRecord.MatchStatus.NEW)
                .centralSyncState(FaceMatchRecord.CentralSyncState.PENDING)
                .syncAttempts(0)
                .attendanceProcessed(false)
                .processAttempts(0)
                .build();

        try {
            return matchRepository.saveAndFlush(record);
        } catch (DataIntegrityViolationException raceLost) {
            // Another thread inserted the same match between the check and the save.
            log.debug("Concurrent insert for match {}, using existing row", matchKey);
            return matchRepository.findByMatchKey(matchKey).orElse(null);
        }
    }

    /** True when the match still has to go through the attendance pipeline. */
    public static boolean needsAttendanceProcessing(FaceMatchRecord record) {
        return record != null && Boolean.FALSE.equals(record.getAttendanceProcessed());
    }

    /** Mark a match as handled by the attendance pipeline. */
    public void markAttendanceProcessed(FaceMatchRecord record) {
        record.setAttendanceProcessed(true);
        record.setProcessError(null);
        matchRepository.save(record);
    }

    /**
     * Record a failed processing attempt. After {@code maxAttempts} the match is given up (marked
     * processed with the error kept) so a permanently broken match cannot hold back the poller.
     */
    public void markAttendanceFailed(FaceMatchRecord record, String error, int maxAttempts) {
        int attempts = (record.getProcessAttempts() != null ? record.getProcessAttempts() : 0) + 1;
        record.setProcessAttempts(attempts);
        record.setProcessError(error != null && error.length() > 500 ? error.substring(0, 500) : error);
        if (attempts >= maxAttempts) {
            record.setAttendanceProcessed(true);
            log.warn("Giving up on match {} after {} attempts: {}", record.getMatchKey(), attempts, error);
        }
        matchRepository.save(record);
    }

    /** {@code cameraId|identifier|second}; identifier prefers the employee certificate, then the PID. */
    private String buildMatchKey(CameraConfig camera, String pid, String certificateNumber,
                                 LocalDateTime matchTime) {
        String identifier = certificateNumber != null && !certificateNumber.isBlank()
                ? certificateNumber.trim()
                : (pid != null && !pid.isBlank() ? pid.trim() : "unknown");
        String second = matchTime != null ? matchTime.format(MATCH_KEY_TIME) : "0";
        return camera.getId() + "|" + identifier + "|" + second;
    }

    /**
     * Get paginated list of match records with filtering.
     */
    @Transactional(readOnly = true)
    public Page<FaceMatchRecordDTO> getMatches(Pageable pageable, Long cameraId, 
                                                String status, LocalDateTime start, LocalDateTime end) {
        // This would use a custom repository method with Specification in a real implementation
        // For now, return all and convert
        List<FaceMatchRecord> records = matchRepository.findAll();
        List<FaceMatchRecordDTO> dtos = records.stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
        
        // Simple pagination (in production, use database pagination)
        int startIdx = (int) pageable.getOffset();
        int endIdx = Math.min(startIdx + pageable.getPageSize(), dtos.size());
        List<FaceMatchRecordDTO> pageContent = dtos.subList(startIdx, endIdx);
        
        return new org.springframework.data.domain.PageImpl<>(
                pageContent, pageable, dtos.size());
    }

    /**
     * Get new (unprocessed) matches.
     */
    @Transactional(readOnly = true)
    public List<FaceMatchRecordDTO> getNewMatches(Double minSimilarity) {
        return matchRepository.findHighConfidenceNewMatches(minSimilarity).stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    /**
     * Paginated, filtered view for the Face Matches page. Every filter is optional. {@code query}
     * matches the camera-reported person name or the certificate/ID number (case-insensitive,
     * substring). Results are newest first.
     */
    @Transactional(readOnly = true)
    public Page<FaceMatchRecordDTO> searchMatches(Pageable pageable, Long cameraId, String status,
                                                  Double minSimilarity, String query,
                                                  LocalDateTime start, LocalDateTime end) {
        FaceMatchRecord.MatchStatus statusEnum = null;
        if (status != null && !status.isBlank()) {
            try {
                statusEnum = FaceMatchRecord.MatchStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException ignored) {
                // Unknown status -> treat as "all".
            }
        }
        String like = (query == null || query.isBlank())
                ? null
                : "%" + query.trim().toLowerCase() + "%";

        return matchRepository.search(statusEnum, cameraId, minSimilarity, start, end, like, pageable)
                .map(this::toDTO);
    }

    /**
     * Bytes of a match's captured face picture, for the in-app photo proxy. The camera reports
     * picture URLs on its own host that the browser cannot reach (digest auth, camera IP), so we
     * fetch through the camera client here and cache the JPEG on disk — the first view hits the
     * camera, later views are served from the cache. Returns empty when there is no picture or the
     * camera could not be reached.
     */
    @Transactional(readOnly = true)
    public Optional<byte[]> getMatchPhoto(Long matchId) {
        FaceMatchRecord match = matchRepository.findById(matchId).orElse(null);
        if (match == null || match.getCamera() == null) {
            return Optional.empty();
        }

        Path cache = Paths.get(faceStorageDir, "matches", matchId + ".jpg");
        try {
            if (Files.isRegularFile(cache)) {
                return Optional.of(Files.readAllBytes(cache));
            }
        } catch (IOException e) {
            log.debug("Could not read cached match photo {}: {}", matchId, e.getMessage());
        }

        String url = match.getFacePicUrl() != null && !match.getFacePicUrl().isBlank()
                ? match.getFacePicUrl()
                : match.getSnapPicUrl();
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }

        Optional<byte[]> bytes = isapiClient.downloadPicture(match.getCamera(), url);
        bytes.ifPresent(data -> {
            try {
                Files.createDirectories(cache.getParent());
                Files.write(cache, data);
            } catch (IOException e) {
                log.debug("Could not cache match photo {}: {}", matchId, e.getMessage());
            }
        });
        return bytes;
    }

    /**
     * Download matches as CSV.
     */
    @Transactional(readOnly = true)
    public FaceMatchSyncResult downloadMatches(Long cameraId, LocalDateTime start, LocalDateTime end,
                                                Double minSimilarity, String format) {
        List<FaceMatchRecord> matches;
        if (cameraId != null) {
            matches = matchRepository.findByCameraIdAndMatchTimeBetweenOrderByMatchTimeDesc(
                    cameraId, start, end);
        } else {
            matches = matchRepository.findByMatchTimeBetweenOrderByMatchTimeDesc(start, end);
        }

        // Filter by similarity if specified
        if (minSimilarity != null) {
            matches = matches.stream()
                    .filter(m -> m.getSimilarity() != null && m.getSimilarity() >= minSimilarity)
                    .collect(Collectors.toList());
        }

        // Mark as exported
        matches.forEach(m -> m.setStatus(FaceMatchRecord.MatchStatus.EXPORTED));
        matchRepository.saveAll(matches);

        // Generate CSV
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintWriter writer = new PrintWriter(baos);
        
        // Header
        writer.println("Match ID,Camera,Camera PID,Certificate Number,Person Name,Similarity,Match Time,Status,Employee ID,Employee Name");
        
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        for (FaceMatchRecord m : matches) {
            writer.printf("%d,%s,%s,%s,%s,%.2f,%s,%s,%s,%s%n",
                    m.getId(),
                    m.getCamera() != null ? m.getCamera().getCameraName() : "",
                    m.getCameraPid() != null ? m.getCameraPid() : "",
                    m.getCertificateNumber() != null ? m.getCertificateNumber() : "",
                    m.getPersonName() != null ? m.getPersonName().replace(",", ";") : "",
                    m.getSimilarity() != null ? m.getSimilarity() : 0.0,
                    m.getMatchTime() != null ? m.getMatchTime().format(formatter) : "",
                    m.getStatus(),
                    m.getEmployeeId() != null ? m.getEmployeeId() : "",
                    ""); // Employee name would need lookup
        }
        writer.flush();
        writer.close();

        String filename = String.format("face_matches_%s_%s.csv",
                start.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")),
                end.format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm")));

        return FaceMatchSyncResult.builder()
                .totalMatches(matches.size())
                .exportData(baos.toByteArray())
                .exportFilename(filename)
                .exportContentType("text/csv")
                .syncTime(LocalDateTime.now())
                .build();
    }

    /**
     * Sync selected matches to the employee database.
     */
    @Transactional
    public FaceMatchSyncResult syncMatchesToDatabase(List<Long> matchIds, String action, 
                                                      String performedBy, Map<Long, String> employeeIdMapping) {
        String batchId = UUID.randomUUID().toString();
        int newEmployees = 0, linked = 0, skipped = 0, errors = 0;
        List<String> messages = new ArrayList<>();

        List<FaceMatchRecord> matches = matchRepository.findAllById(matchIds);

        for (FaceMatchRecord match : matches) {
            try {
                switch (action.toUpperCase()) {
                    case "IMPORT" -> {
                        // Create new employee from match
                        Employee employee = createEmployeeFromMatch(match, employeeIdMapping.get(match.getId()));
                        match.setEmployeeId(employee.getId());
                        match.setStatus(FaceMatchRecord.MatchStatus.PROCESSED);
                        match.setProcessedBy(performedBy);
                        match.setProcessedAt(LocalDateTime.now());
                        newEmployees++;
                        messages.add("Imported " + match.getPersonName() + " as employee " + employee.getEmployeeId());
                        
                        recordSyncHistory(match, SyncHistory.SyncOperation.IMPORT, performedBy, batchId, 
                                SyncHistory.SyncResultStatus.SUCCESS, null, employee.getEmployeeId());
                    }
                    case "LINK" -> {
                        // Link to existing employee
                        String targetEmployeeId = employeeIdMapping.get(match.getId());
                        if (targetEmployeeId == null) {
                            skipped++;
                            messages.add("Skipped " + match.getPersonName() + " - no target employee specified");
                            continue;
                        }
                        Employee employee = employeeRepository.findByEmployeeId(targetEmployeeId)
                                .orElseThrow(() -> new RuntimeException("Employee not found: " + targetEmployeeId));
                        
                        match.setEmployeeId(employee.getId());
                        match.setStatus(FaceMatchRecord.MatchStatus.PROCESSED);
                        match.setProcessedBy(performedBy);
                        match.setProcessedAt(LocalDateTime.now());
                        linked++;
                        messages.add("Linked " + match.getPersonName() + " to employee " + employee.getEmployeeId());
                        
                        recordSyncHistory(match, SyncHistory.SyncOperation.LINK, performedBy, batchId,
                                SyncHistory.SyncResultStatus.SUCCESS, null, employee.getEmployeeId());
                    }
                    case "IGNORE" -> {
                        match.setStatus(FaceMatchRecord.MatchStatus.IGNORED);
                        match.setProcessedBy(performedBy);
                        match.setProcessedAt(LocalDateTime.now());
                        skipped++;
                        messages.add("Ignored " + match.getPersonName());
                    }
                    default -> {
                        skipped++;
                        messages.add("Unknown action: " + action);
                    }
                }
                matchRepository.save(match);
            } catch (Exception e) {
                errors++;
                messages.add("Error processing " + match.getPersonName() + ": " + e.getMessage());
                log.error("Error processing match {}", match.getId(), e);
                
                recordSyncHistory(match, SyncHistory.SyncOperation.IMPORT, performedBy, batchId,
                        SyncHistory.SyncResultStatus.FAILED, e.getMessage(), null);
            }
        }

        return FaceMatchSyncResult.builder()
                .totalMatches(matches.size())
                .newEmployees(newEmployees)
                .linkedEmployees(linked)
                .skipped(skipped)
                .errors(errors)
                .messages(messages)
                .syncBatchId(batchId)
                .syncTime(LocalDateTime.now())
                .build();
    }

    /**
     * Get match statistics for dashboard.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> getMatchStatistics() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalNew", matchRepository.countByStatus(FaceMatchRecord.MatchStatus.NEW));
        stats.put("totalProcessed", matchRepository.countByStatus(FaceMatchRecord.MatchStatus.PROCESSED));
        stats.put("totalIgnored", matchRepository.countByStatus(FaceMatchRecord.MatchStatus.IGNORED));
        stats.put("totalExported", matchRepository.countByStatus(FaceMatchRecord.MatchStatus.EXPORTED));
        
        // Matches in last 24 hours
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        List<FaceMatchRecord> recent = matchRepository.findByMatchTimeBetweenOrderByMatchTimeDesc(
                yesterday, LocalDateTime.now());
        stats.put("last24Hours", recent.size());
        
        return stats;
    }

    private Employee createEmployeeFromMatch(FaceMatchRecord match, String customEmployeeId) {
        String employeeId = customEmployeeId != null ? customEmployeeId : match.getCertificateNumber();
        if (employeeId == null || employeeId.isBlank()) {
            employeeId = "AUTO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        }

        // Check if employee already exists
        Optional<Employee> existing = employeeRepository.findByEmployeeId(employeeId);
        if (existing.isPresent()) {
            throw new RuntimeException("Employee already exists: " + employeeId);
        }

        Employee employee = Employee.builder()
                .employeeId(employeeId)
                .fullName(match.getPersonName() != null ? match.getPersonName() : "Unknown")
                .faceTemplateId(match.getCameraPid())
                .isActive(true)
                .syncStatus(SyncStatus.PENDING)
                .build();

        return employeeRepository.save(employee);
    }

    private void recordSyncHistory(FaceMatchRecord match, SyncHistory.SyncOperation operation,
                                   String performedBy, String batchId, 
                                   SyncHistory.SyncResultStatus status, String errorMessage, String employeeNumber) {
        SyncHistory history = SyncHistory.builder()
                .camera(match.getCamera())
                .operation(operation)
                .employeeId(match.getEmployeeId())
                .employeeNumber(employeeNumber != null ? employeeNumber : match.getCertificateNumber())
                .cameraPid(match.getCameraPid())
                .performedBy(performedBy)
                .status(status)
                .errorMessage(errorMessage)
                .syncBatchId(batchId)
                .build();
        syncHistoryRepository.save(history);
    }

    private FaceMatchRecordDTO toDTO(FaceMatchRecord record) {
        String employeeName = null;
        if (record.getEmployeeId() != null) {
            employeeName = employeeRepository.findById(record.getEmployeeId())
                    .map(Employee::getFullName)
                    .orElse(null);
        }

        return FaceMatchRecordDTO.builder()
                .id(record.getId())
                .cameraPid(record.getCameraPid())
                .certificateNumber(record.getCertificateNumber())
                .personName(record.getPersonName())
                .similarity(record.getSimilarity())
                .matchTime(record.getMatchTime())
                .cameraId(record.getCamera() != null ? record.getCamera().getId() : null)
                .cameraName(record.getCamera() != null ? record.getCamera().getCameraName() : null)
                .facePicUrl(record.getFacePicUrl())
                .snapPicUrl(record.getSnapPicUrl())
                .status(record.getStatus().name())
                .processedBy(record.getProcessedBy())
                .processedAt(record.getProcessedAt())
                .employeeId(record.getEmployeeId())
                .employeeName(employeeName)
                .hasPhoto(record.getFacePicUrl() != null || record.getSnapPicUrl() != null)
                .build();
    }
}
