package com.attendance.service;

import com.attendance.dto.FaceEvent;
import com.attendance.entity.CameraConfig;
import com.attendance.entity.Employee;
import com.attendance.repository.CameraConfigRepository;
import com.attendance.repository.EmployeeCameraTargetRepository;
import com.attendance.repository.EmployeeRepository;
import com.attendance.service.insightface.FaceMatcher;
import com.attendance.service.insightface.FaceLearningService;
import com.attendance.service.insightface.InsightFaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns one face event from a camera into an attendance record. Shared by the
 * real-time stream watcher and the (fallback) polling mode.
 *
 * Steps: per-person cooldown, save the captured picture to the 24-hour face
 * capture library, map the camera-reported identifier to an {@link Employee},
 * similarity check, then record attendance via
 * {@link AttendanceService#processDetection} (with a persistent picture copy).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class FaceEventProcessor {

    private final EmployeeRepository employeeRepository;
    private final EmployeeCameraTargetRepository targetRepository;
    private final CameraConfigRepository cameraRepository;
    private final HikvisionIsapiClient isapiClient;
    private final AttendanceService attendanceService;
    private final FaceCaptureService faceCaptureService;
    private final InsightFaceService insightFaceService;
    private final FaceLearningService faceLearningService;

    @Value("${attendance.face-storage-dir:faces}")
    private String faceStorageDir;

    @Value("${attendance.similarity.threshold:80}")
    private double similarityThreshold;

    @Value("${attendance.camera.cooldown-seconds:60}")
    private long cooldownSeconds;

    /**
     * Event time of the last accepted event per person (camera + identifier), for the cooldown.
     * Event time (not arrival time) is used so that matches replayed later by the catch-up sync —
     * e.g. a morning check-in and an evening check-out handled in the same second — are not
     * mistaken for duplicates.
     */
    private final Map<String, Instant> lastAcceptedByPerson = new ConcurrentHashMap<>();

    public void process(CameraConfig camera, FaceEvent event, byte[] picture) {
        String identifier = event.getEmployeeNo() != null && !event.getEmployeeNo().isBlank()
                ? event.getEmployeeNo().trim()
                : (event.getPid() != null && !event.getPid().isBlank()
                ? "pid:" + event.getPid().trim()
                : (event.getPersonName() != null && !event.getPersonName().isBlank()
                ? "name:" + event.getPersonName().trim() : "unknown"));
        String cooldownKey = camera.getId() + ":" + identifier;

        Instant now = event.getEventTimeMillis() != null ? Instant.ofEpochMilli(event.getEventTimeMillis()) : Instant.now();
        Instant last = lastAcceptedByPerson.get(cooldownKey);
        if (last != null && Math.abs(Duration.between(last, now).getSeconds()) < cooldownSeconds) {
            log.debug("Cooldown: skipping {} on {} (seen {}s apart)", identifier,
                    camera.getIpAddress(), Math.abs(Duration.between(last, now).getSeconds()));
            return;
        }

        Optional<Employee> employeeOpt = resolveEmployee(camera, event);

        // Low-similarity recognition of a known face: skip without consuming the
        // cooldown slot, so a better capture a second later still gets through.
        if (employeeOpt.isPresent() && event.getSimilarity() != null
                && event.getSimilarity() < similarityThreshold) {
            log.info("Skipping low-similarity detection for {} ({}% < {}%)",
                    employeeOpt.get().getEmployeeId(), event.getSimilarity(), similarityThreshold);
            return;
        }

        // Resolve the JPEG once: from the stream part, or by downloading the URL the
        // camera reported. Every accepted event lands in the 24h face capture library;
        // matched employees additionally get a persistent copy on their attendance record.
        byte[] pictureBytes = resolvePictureBytes(camera, event, picture);

        // --- The camera recognized the employee: record attendance, after an optional ---
        // --- InsightFace second opinion (insightface.verify.enabled).                 ---
        if (employeeOpt.isPresent()) {
            Employee employee = employeeOpt.get();
            lastAcceptedByPerson.put(cooldownKey, now);
            if (insightFaceService.isEnabled() && pictureBytes != null && pictureBytes.length > 0
                    && holdForReview(camera, event, employee, pictureBytes)) {
                return;
            }
            faceCaptureService.recordCapture(camera, event, pictureBytes, employee);
            recordAttendance(camera, event, employee, pictureBytes, event.getSimilarity(), "CAMERA");
            return;
        }

        // --- The camera did NOT recognize the face: try the InsightFace fallback layer. ---
        lastAcceptedByPerson.put(cooldownKey, now);
        if (insightFaceService.isEnabled() && pictureBytes != null && pictureBytes.length > 0) {
            InsightFaceService.RecognitionResult result =
                    insightFaceService.recognize(pictureBytes, () -> downloadBackgroundPicture(camera, event));
            if (result.band() == FaceMatcher.Band.HIGH && result.candidate() != null) {
                // High confidence -> validate and record attendance just like a camera match.
                Employee employee = result.candidate();
                faceCaptureService.recordCapture(camera, event, pictureBytes, employee);
                recordAttendance(camera, event, employee, pictureBytes, result.score() * 100.0, "INSIGHTFACE");
                // Stamp the cooldown under the identified employee too, so a camera event for the
                // same person moments later on another ingestion path respects the window.
                lastAcceptedByPerson.put(camera.getId() + ":" + employee.getEmployeeId(), now);
                log.info("InsightFace recognized {} ({}) score {} on {}", employee.getEmployeeId(),
                        employee.getFullName(), String.format("%.3f", result.score()), camera.getIpAddress());
                // Very confident matches may become new reference samples (off by default).
                faceLearningService.considerAutoLearn(employee, result);
                return;
            }
            if (result.band() == FaceMatcher.Band.UNCERTAIN && result.candidate() != null) {
                // Uncertain -> queue for a human to confirm/reject (no attendance yet).
                Employee alternative = "AMBIGUOUS".equals(result.reason()) && result.runnerUpDbId() != null
                        ? employeeRepository.findById(result.runnerUpDbId()).orElse(null) : null;
                faceCaptureService.recordUncertainMatch(camera, event, pictureBytes, result.candidate(), result.score(),
                        insightFaceService.describeUncertain(result), "INSIGHTFACE",
                        alternative, alternative != null ? result.runnerUpScore() : null);
                return;
            }
        }

        // No match (or fallback disabled/unavailable): keep today's behavior — save the unknown capture.
        faceCaptureService.recordCapture(camera, event, pictureBytes, null);
        log.info("Face event from {} did not match any employee (id={}, name={})",
                camera.getIpAddress(), event.getEmployeeNo(), event.getPersonName());
    }

    /**
     * Phase 5: ask InsightFace whether it agrees with the camera's match. Returns true when the
     * event was put in the review queue instead of recording attendance:
     * <ul>
     *   <li>CONFLICT — another employee matches clearly better: always reviewed, and the reviewer
     *       can pick either person;</li>
     *   <li>WEAK — the face does not look like the employee's photos: reviewed when
     *       {@code insightface.verify.on-weak=REVIEW} (default), accepted when ACCEPT.</li>
     * </ul>
     * AGREE and NOT_CHECKED (no usable face, no photos, service down) keep the camera's decision.
     */
    private boolean holdForReview(CameraConfig camera, FaceEvent event, Employee employee, byte[] pictureBytes) {
        InsightFaceService.VerificationResult check = insightFaceService.verifyCameraMatch(
                employee, pictureBytes, () -> downloadBackgroundPicture(camera, event));
        switch (check.outcome()) {
            case CONFLICT -> {
                String note = String.format("Camera said %s (%s, %.0f%%), but the face looks more like %s (%s): %.2f vs %.2f",
                        employee.getFullName(), employee.getEmployeeId(),
                        event.getSimilarity() != null ? event.getSimilarity() : 0.0,
                        check.other().getFullName(), check.other().getEmployeeId(),
                        check.otherScore(), check.cameraScore());
                log.warn("InsightFace disagrees with camera {} on {}: {}", camera.getIpAddress(),
                        employee.getEmployeeId(), note);
                faceCaptureService.recordUncertainMatch(camera, event, pictureBytes, employee, check.cameraScore(),
                        note, "CAMERA_VERIFY", check.other(), check.otherScore());
                return true;
            }
            case WEAK -> {
                String note = String.format("Camera said %s (%.0f%%), but the face does not look like their photos "
                                + "(%.2f < %.2f)", employee.getFullName(),
                        event.getSimilarity() != null ? event.getSimilarity() : 0.0,
                        check.cameraScore(), insightFaceService.lowThreshold());
                if (insightFaceService.verifyWeakGoesToReview()) {
                    log.info("InsightFace could not confirm camera match {} on {}: {}", employee.getEmployeeId(),
                            camera.getIpAddress(), note);
                    faceCaptureService.recordUncertainMatch(camera, event, pictureBytes, employee, check.cameraScore(),
                            note, "CAMERA_VERIFY");
                    return true;
                }
                log.info("Accepting camera match despite weak InsightFace score: {}", note);
                return false;
            }
            case AGREE -> {
                if (check.recognition() != null) {
                    faceLearningService.considerAutoLearn(employee, check.recognition());
                }
                return false;
            }
            default -> {
                log.debug("Camera match of {} not checked by InsightFace: {}", employee.getEmployeeId(), check.reason());
                return false;
            }
        }
    }

    /** Persist the event picture and record an attendance detection for the given employee. */
    private void recordAttendance(CameraConfig camera, FaceEvent event, Employee employee,
                                  byte[] pictureBytes, Double similarity, String source) {
        String storedImagePath = savePicture(camera, pictureBytes);
        String deviceId = event.getDeviceId() != null ? event.getDeviceId() : camera.getCameraName();
        String direction = determineDirection(employee.getId(), event.getEventTimeMillis());
        attendanceService.processDetection(
                employee.getEmployeeId(),
                deviceId,
                similarity,
                direction,
                storedImagePath,
                event.getEventTimeMillis(),
                source);
        markConnected(camera);
    }

    /**
     * Map the camera's identifier to an Employee: the per-camera PID link first, then the
     * employeeId (sent to the camera as certificateNumber), then faceTemplateId (a PID), then
     * the camera-reported name.
     */
    private Optional<Employee> resolveEmployee(CameraConfig camera, FaceEvent event) {
        // The camera-local PID is the most precise link: it is recorded per camera when a face is
        // enrolled or a library sync links the person (employee_camera_targets).
        if (event.getPid() != null && !event.getPid().isBlank()) {
            Optional<Employee> byPid = targetRepository.findFirstByCameraIdAndPid(camera.getId(), event.getPid().trim())
                    .map(t -> t.getEmployee().getId())
                    .flatMap(employeeRepository::findById);
            if (byPid.isPresent()) return byPid;
        }
        if (event.getEmployeeNo() != null && !event.getEmployeeNo().isBlank()) {
            String no = event.getEmployeeNo().trim();

            // Employee ID first: camera PIDs are small numbers ("12") that could otherwise collide
            // with another employee's ID and pick the wrong person.
            Optional<Employee> byEmployeeId = employeeRepository.findByEmployeeId(no);
            if (byEmployeeId.isPresent()) return byEmployeeId;

            Optional<Employee> byTemplate = employeeRepository.findByFaceTemplateId(no);
            if (byTemplate.isPresent()) return byTemplate;
        }
        if (event.getPid() != null && !event.getPid().isBlank()) {
            Optional<Employee> byTemplate = employeeRepository.findByFaceTemplateId(event.getPid().trim());
            if (byTemplate.isPresent()) return byTemplate;
        }

        if (event.getPersonName() != null && !event.getPersonName().isBlank()) {
            String name = event.getPersonName().trim();
            return employeeRepository.findByIsActiveTrue().stream()
                    .filter(e -> name.equalsIgnoreCase(e.getFullName())
                            || (e.getFullNameKurdish() != null && name.equalsIgnoreCase(e.getFullNameKurdish())))
                    .findFirst();
        }
        return Optional.empty();
    }

    /** First accepted detection of the day is a check-IN; a later one is a check-OUT. */
    private String determineDirection(Long employeeId, Long eventTimeMillis) {
        // Use the event's own day, so a match replayed after midnight lands on the right day.
        java.time.LocalDate day = eventTimeMillis != null
                ? LocalDateTime.ofInstant(Instant.ofEpochMilli(eventTimeMillis), java.time.ZoneId.systemDefault()).toLocalDate()
                : java.time.LocalDate.now();
        boolean alreadyCheckedIn = attendanceService.hasOpenCheckIn(employeeId, day);
        return alreadyCheckedIn ? "OUT" : "IN";
    }

    /** Get the event JPEG: the stream part when present, otherwise the camera's picture URL. */
    private byte[] resolvePictureBytes(CameraConfig camera, FaceEvent event, byte[] picture) {
        if (picture != null && picture.length > 0) {
            return picture;
        }
        if (event.getPictureUrl() != null && !event.getPictureUrl().isBlank()) {
            return isapiClient.downloadPicture(camera, event.getPictureUrl()).orElse(null);
        }
        return null;
    }

    /** The event's second picture (wider scene), downloaded on demand; null when there is none. */
    private byte[] downloadBackgroundPicture(CameraConfig camera, FaceEvent event) {
        String url = event.getBackgroundPictureUrl();
        if (url == null || url.isBlank() || url.equals(event.getPictureUrl())) {
            return null;
        }
        return isapiClient.downloadPicture(camera, url).orElse(null);
    }

    /** Save the event JPEG to disk and return the public URL path, or null if unavailable. */
    private String savePicture(CameraConfig camera, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        try {
            Path dir = Paths.get(faceStorageDir);
            Files.createDirectories(dir);

            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            String filename = camera.getId() + "_" + ts + ".jpg";
            Files.write(dir.resolve(filename), bytes, StandardOpenOption.CREATE);

            return "/faces/" + filename;
        } catch (IOException e) {
            log.warn("Failed to save face image: {}", e.getMessage());
            return null;
        }
    }

    public void markConnected(CameraConfig camera) {
        boolean stale = camera.getLastSync() == null
                || camera.getLastSync().isBefore(LocalDateTime.now().minusSeconds(60));
        if (!Boolean.TRUE.equals(camera.getIsConnected()) || stale) {
            camera.setIsConnected(true);
            camera.setLastSync(LocalDateTime.now());
            cameraRepository.save(camera);
        }
    }

    public void markDisconnected(CameraConfig camera) {
        if (Boolean.TRUE.equals(camera.getIsConnected())) {
            camera.setIsConnected(false);
            cameraRepository.save(camera);
        }
    }

    /** Drop cooldown entries that can no longer match anything, so the map stays small. */
    @Scheduled(fixedDelay = 600_000)
    public void cleanupCooldowns() {
        Instant cutoff = Instant.now().minus(Duration.ofSeconds(Math.max(cooldownSeconds * 10, 3600)));
        lastAcceptedByPerson.values().removeIf(t -> t.isBefore(cutoff));
    }
}
