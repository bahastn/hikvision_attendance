package com.attendance.service;

import com.attendance.dto.FaceEvent;
import com.attendance.entity.CameraConfig;
import com.attendance.entity.Employee;
import com.attendance.repository.CameraConfigRepository;
import com.attendance.repository.EmployeeRepository;
import com.attendance.service.insightface.FaceMatcher;
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
    private final CameraConfigRepository cameraRepository;
    private final HikvisionIsapiClient isapiClient;
    private final AttendanceService attendanceService;
    private final FaceCaptureService faceCaptureService;
    private final InsightFaceService insightFaceService;

    @Value("${attendance.face-storage-dir:faces}")
    private String faceStorageDir;

    @Value("${attendance.similarity.threshold:80}")
    private double similarityThreshold;

    @Value("${attendance.camera.cooldown-seconds:60}")
    private long cooldownSeconds;

    /** Last time an event was accepted per person (camera + identifier), for the cooldown. */
    private final Map<String, Instant> lastAcceptedByPerson = new ConcurrentHashMap<>();

    public void process(CameraConfig camera, FaceEvent event, byte[] picture) {
        String identifier = event.getEmployeeNo() != null && !event.getEmployeeNo().isBlank()
                ? event.getEmployeeNo().trim()
                : (event.getPersonName() != null && !event.getPersonName().isBlank()
                ? "name:" + event.getPersonName().trim() : "unknown");
        String cooldownKey = camera.getId() + ":" + identifier;

        Instant now = Instant.now();
        Instant last = lastAcceptedByPerson.get(cooldownKey);
        if (last != null && Duration.between(last, now).getSeconds() < cooldownSeconds) {
            log.debug("Cooldown: skipping {} on {} (seen {}s ago)", identifier,
                    camera.getIpAddress(), Duration.between(last, now).getSeconds());
            return;
        }

        Optional<Employee> employeeOpt = resolveEmployee(event);

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

        // --- The camera recognized the employee: record attendance (unchanged behavior). ---
        if (employeeOpt.isPresent()) {
            Employee employee = employeeOpt.get();
            faceCaptureService.recordCapture(camera, event, pictureBytes, employee);
            recordAttendance(camera, event, employee, pictureBytes, event.getSimilarity(), "CAMERA");
            lastAcceptedByPerson.put(cooldownKey, now);
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
                return;
            }
            if (result.band() == FaceMatcher.Band.UNCERTAIN && result.candidate() != null) {
                // Uncertain -> queue for a human to confirm/reject (no attendance yet).
                faceCaptureService.recordUncertainMatch(camera, event, pictureBytes, result.candidate(), result.score(),
                        insightFaceService.describeUncertain(result), "INSIGHTFACE");
                return;
            }
        }

        // No match (or fallback disabled/unavailable): keep today's behavior — save the unknown capture.
        faceCaptureService.recordCapture(camera, event, pictureBytes, null);
        log.info("Face event from {} did not match any employee (id={}, name={})",
                camera.getIpAddress(), event.getEmployeeNo(), event.getPersonName());
    }

    /** Persist the event picture and record an attendance detection for the given employee. */
    private void recordAttendance(CameraConfig camera, FaceEvent event, Employee employee,
                                  byte[] pictureBytes, Double similarity, String source) {
        String storedImagePath = savePicture(camera, pictureBytes);
        String deviceId = event.getDeviceId() != null ? event.getDeviceId() : camera.getCameraName();
        String direction = determineDirection(employee.getId());
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
     * Map the camera's identifier to an Employee. Tries faceTemplateId (the PID
     * assigned at enrollment) first, then employeeId (sent as certificateNumber),
     * then the camera-reported name.
     */
    private Optional<Employee> resolveEmployee(FaceEvent event) {
        if (event.getEmployeeNo() != null && !event.getEmployeeNo().isBlank()) {
            String no = event.getEmployeeNo().trim();

            Optional<Employee> byTemplate = employeeRepository.findByFaceTemplateId(no);
            if (byTemplate.isPresent()) return byTemplate;

            Optional<Employee> byEmployeeId = employeeRepository.findByEmployeeId(no);
            if (byEmployeeId.isPresent()) return byEmployeeId;
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
    private String determineDirection(Long employeeId) {
        boolean alreadyCheckedInToday = attendanceService.hasOpenCheckInToday(employeeId);
        return alreadyCheckedInToday ? "OUT" : "IN";
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
