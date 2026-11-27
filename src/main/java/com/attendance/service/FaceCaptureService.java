package com.attendance.service;

import com.attendance.dto.EmployeeDTO;
import com.attendance.dto.FaceCaptureDTO;
import com.attendance.dto.FaceEvent;
import com.attendance.entity.CameraConfig;
import com.attendance.entity.Employee;
import com.attendance.entity.FaceCapture;
import com.attendance.repository.EmployeeRepository;
import com.attendance.repository.FaceCaptureRepository;
import com.attendance.repository.FaceReviewOutcomeRepository;
import com.attendance.entity.FaceReviewOutcome;
import lombok.RequiredArgsConstructor;
import com.attendance.service.insightface.FaceEmbeddingEvents;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * Rolling 24-hour library of every face picture captured by the cameras.
 * {@link FaceEventProcessor} calls {@link #recordCapture} for each accepted event;
 * a scheduled task deletes rows (and their files) once the retention window ends.
 * A capture can also be turned into an employee: the picture is copied to the
 * employee and pushed to the face library of the selected cameras.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaceCaptureService {

    private final FaceCaptureRepository captureRepository;
    private final EmployeeService employeeService;
    private final FaceEnrollmentService faceEnrollmentService;
    private final AttendanceService attendanceService;
    private final EmployeeRepository employeeRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final FaceReviewOutcomeRepository reviewOutcomeRepository;

    @Value("${attendance.face-storage-dir:faces}")
    private String faceStorageDir;

    /** How long a captured face is kept before it is deleted (default 24 hours). */
    @Value("${attendance.face-capture.retention-hours:24}")
    private long retentionHours;

    /**
     * Store one captured face in the library. No-op when there is no picture.
     * employee may be null (unrecognised face).
     */
    @Transactional
    public void recordCapture(CameraConfig camera, FaceEvent event, byte[] picture, Employee employee) {
        if (picture == null || picture.length == 0) {
            return;
        }
        try {
            String imageUrl = writeCaptureFile(camera.getId(), picture);
            LocalDateTime capturedAt = capturedAtOf(event);
            captureRepository.save(FaceCapture.builder()
                    .cameraId(camera.getId())
                    .cameraName(camera.getCameraName())
                    .imageUrl(imageUrl)
                    .matchedEmployeeId(employee != null ? employee.getEmployeeId() : event.getEmployeeNo())
                    .matchedEmployeeName(employee != null ? employee.getFullName() : null)
                    .personName(event.getPersonName())
                    .similarity(event.getSimilarity())
                    .recognitionSource("CAMERA")
                    .reviewStatus(FaceCapture.ReviewStatus.NONE)
                    .capturedAt(capturedAt)
                    .expiresAt(capturedAt.plusHours(retentionHours))
                    .build());
        } catch (IOException e) {
            log.warn("Failed to store captured face: {}", e.getMessage());
        }
    }

    /**
     * Store an uncertain InsightFace suggestion as a capture awaiting human review. The reviewer can
     * confirm (records attendance) or reject it. These rows are exempt from the retention purge until
     * reviewed, so a pending decision is never silently lost.
     */
    @Transactional
    public void recordUncertainMatch(CameraConfig camera, FaceEvent event, byte[] picture,
                                     Employee candidate, double score) {
        recordUncertainMatch(camera, event, picture, candidate, score, null, "INSIGHTFACE");
    }

    /**
     * Queue a capture for review with a note for the reviewer. {@code source} is the recognizer
     * whose decision is being reviewed ({@code INSIGHTFACE}, or {@code CAMERA_VERIFY} when
     * InsightFace could not confirm a camera match).
     */
    @Transactional
    public void recordUncertainMatch(CameraConfig camera, FaceEvent event, byte[] picture,
                                     Employee candidate, double score, String note, String source) {
        if (picture == null || picture.length == 0 || candidate == null) {
            return;
        }
        try {
            String imageUrl = writeCaptureFile(camera.getId(), picture);
            LocalDateTime capturedAt = capturedAtOf(event);
            captureRepository.save(FaceCapture.builder()
                    .cameraId(camera.getId())
                    .cameraName(camera.getCameraName())
                    .imageUrl(imageUrl)
                    .personName(event.getPersonName())
                    .similarity(event.getSimilarity())
                    .recognitionSource(source != null ? source : "INSIGHTFACE")
                    .candidateEmployeeId(candidate.getEmployeeId())
                    .candidateEmployeeName(candidate.getFullName())
                    .candidateScore(score)
                    .matchNote(note != null && note.length() > 500 ? note.substring(0, 500) : note)
                    .reviewStatus(FaceCapture.ReviewStatus.PENDING_REVIEW)
                    .capturedAt(capturedAt)
                    .expiresAt(capturedAt.plusHours(retentionHours))
                    .build());
            log.info("Uncertain InsightFace match queued for review: candidate {} ({}), score {}",
                    candidate.getEmployeeId(), candidate.getFullName(), String.format("%.3f", score));
        } catch (IOException e) {
            log.warn("Failed to store uncertain face match: {}", e.getMessage());
        }
    }

    /** Write a captured JPEG to the captures directory and return its public URL path. */
    private String writeCaptureFile(Long cameraId, byte[] picture) throws IOException {
        Path dir = Paths.get(faceStorageDir, "captures");
        Files.createDirectories(dir);
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        String filename = cameraId + "_" + ts + "_" + ThreadLocalRandom.current().nextInt(1000) + ".jpg";
        Files.write(dir.resolve(filename), picture);
        return "/faces/captures/" + filename;
    }

    private LocalDateTime capturedAtOf(FaceEvent event) {
        return event.getEventTimeMillis() != null
                ? LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(event.getEventTimeMillis()),
                        java.time.ZoneId.systemDefault())
                : LocalDateTime.now();
    }

    /** Captures awaiting human review (uncertain InsightFace suggestions), newest first. */
    @Transactional(readOnly = true)
    public List<FaceCaptureDTO> listPendingReview() {
        return captureRepository.findByReviewStatusOrderByCapturedAtDesc(FaceCapture.ReviewStatus.PENDING_REVIEW)
                .stream().map(this::toDTO).collect(Collectors.toList());
    }

    /**
     * Confirm a pending review: record attendance for the chosen employee and mark the capture
     * CONFIRMED. {@code employeeId} defaults to the stored candidate when blank.
     */
    @Transactional
    public void confirmPendingReview(Long captureId, String employeeId) {
        FaceCapture capture = captureRepository.findById(captureId)
                .orElseThrow(() -> new RuntimeException("Face capture not found"));
        String targetId = (employeeId != null && !employeeId.isBlank())
                ? employeeId.trim() : capture.getCandidateEmployeeId();
        if (targetId == null || targetId.isBlank()) {
            throw new RuntimeException("No employee to confirm for this capture");
        }
        Employee employee = employeeRepository.findByEmployeeId(targetId)
                .orElseThrow(() -> new RuntimeException("Employee not found: " + targetId));

        String direction = attendanceService.hasOpenCheckInToday(employee.getId()) ? "OUT" : "IN";
        Double scorePercent = capture.getCandidateScore() != null ? capture.getCandidateScore() * 100.0 : null;
        // Record the attendance at the moment the face was captured, not when the reviewer clicked.
        Long capturedAtMillis = capture.getCapturedAt() != null
                ? capture.getCapturedAt().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                : null;
        // A camera match that InsightFace could not confirm stays a camera recognition once confirmed.
        String source = "CAMERA_VERIFY".equals(capture.getRecognitionSource()) ? "CAMERA" : "INSIGHTFACE";
        attendanceService.processDetection(
                employee.getEmployeeId(),
                capture.getCameraName() != null ? capture.getCameraName() : "INSIGHTFACE",
                scorePercent,
                direction,
                capture.getImageUrl(),
                capturedAtMillis,
                source);

        capture.setReviewStatus(FaceCapture.ReviewStatus.CONFIRMED);
        capture.setMatchedEmployeeId(employee.getEmployeeId());
        capture.setMatchedEmployeeName(employee.getFullName());
        captureRepository.save(capture);
        logReviewOutcome(capture, employee.getEmployeeId().equals(capture.getCandidateEmployeeId()));

        // Let InsightFace learn from the confirmed capture (after commit, async; no-op when off).
        eventPublisher.publishEvent(new FaceEmbeddingEvents.CaptureConfirmed(
                capture.getId(), employee.getId(), capture.getImageUrl(), currentUsername()));
    }

    private static String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated() ? auth.getName() : null;
    }

    /** Reject a pending review: no attendance is recorded; the capture is marked REJECTED. */
    @Transactional
    public void rejectPendingReview(Long captureId) {
        FaceCapture capture = captureRepository.findById(captureId)
                .orElseThrow(() -> new RuntimeException("Face capture not found"));
        capture.setReviewStatus(FaceCapture.ReviewStatus.REJECTED);
        captureRepository.save(capture);
        logReviewOutcome(capture, false);
    }

    /** Keep an image-free record of the decision for threshold calibration. */
    private void logReviewOutcome(FaceCapture capture, boolean confirmedCandidate) {
        if (capture.getCandidateScore() == null) {
            return;
        }
        reviewOutcomeRepository.save(FaceReviewOutcome.builder()
                .captureId(capture.getId())
                .source(capture.getRecognitionSource())
                .candidateEmployeeId(capture.getCandidateEmployeeId())
                .candidateScore(capture.getCandidateScore())
                .confirmedCandidate(confirmedCandidate)
                .build());
    }

    /** Captures still within the retention window, newest first. cameraId == null means all cameras. */
    @Transactional(readOnly = true)
    public List<FaceCaptureDTO> listActiveCaptures(Long cameraId) {
        List<FaceCapture> captures = cameraId != null
                ? captureRepository.findByCameraIdAndExpiresAtAfterOrderByCapturedAtDesc(cameraId, LocalDateTime.now())
                : captureRepository.findByExpiresAtAfterOrderByCapturedAtDesc(LocalDateTime.now());
        return captures.stream().map(this::toDTO).collect(Collectors.toList());
    }

    /** Delete one capture: database row plus its image file. */
    @Transactional
    public void deleteCapture(Long id) {
        captureRepository.findById(id).ifPresent(capture -> {
            deleteFileQuietly(capture.getImageUrl());
            captureRepository.delete(capture);
        });
    }

    /**
     * Turn a captured face into an employee: create the employee, then push the
     * picture to the face library of the selected cameras (all active ones when
     * cameraIds is null/empty). Rolls back as a unit when a selected camera rejects
     * the photo.
     */
    @Transactional
    public FaceEnrollmentService.EnrollmentResult enrollCaptureAsEmployee(
            Long captureId, EmployeeDTO employeeDTO, List<Long> cameraIds) {
        FaceCapture capture = captureRepository.findById(captureId)
                .orElseThrow(() -> new RuntimeException("Face capture not found"));
        byte[] picture = readCaptureImage(capture);

        EmployeeDTO employee = employeeService.createEmployee(employeeDTO);
        return faceEnrollmentService.enrollFace(employee.getId(), picture, cameraIds);
    }

    /** JPEG bytes of a stored capture. */
    @Transactional(readOnly = true)
    public byte[] readCaptureImage(Long captureId) {
        FaceCapture capture = captureRepository.findById(captureId)
                .orElseThrow(() -> new RuntimeException("Face capture not found"));
        return readCaptureImage(capture);
    }

    private byte[] readCaptureImage(FaceCapture capture) {
        try {
            return Files.readAllBytes(resolveImagePath(capture.getImageUrl()));
        } catch (IOException e) {
            throw new RuntimeException("Could not read the captured photo: " + e.getMessage());
        }
    }

    /** Delete expired captures and their files. Runs on a fixed delay; safe to call any time. */
    @Scheduled(fixedDelayString = "${attendance.face-capture.cleanup-interval-ms:1800000}",
            initialDelayString = "${attendance.face-capture.cleanup-interval-ms:1800000}")
    @Transactional
    public void cleanupExpiredCaptures() {
        // Exclude PENDING_REVIEW so an uncertain InsightFace suggestion is never purged before a
        // reviewer decides on it.
        List<FaceCapture> expired = captureRepository.findExpiredForCleanup(
                LocalDateTime.now(), FaceCapture.ReviewStatus.PENDING_REVIEW);
        for (FaceCapture capture : expired) {
            deleteFileQuietly(capture.getImageUrl());
            captureRepository.delete(capture);
        }
        if (!expired.isEmpty()) {
            log.info("Purged {} expired face capture(s)", expired.size());
        }
    }

    private FaceCaptureDTO toDTO(FaceCapture c) {
        return FaceCaptureDTO.builder()
                .id(c.getId())
                .cameraId(c.getCameraId())
                .cameraName(c.getCameraName())
                .imageUrl(c.getImageUrl())
                .capturedAt(c.getCapturedAt() != null ? c.getCapturedAt().toString() : null)
                .expiresAt(c.getExpiresAt() != null ? c.getExpiresAt().toString() : null)
                .matchedEmployeeId(c.getMatchedEmployeeId())
                .matchedEmployeeName(c.getMatchedEmployeeName())
                .personName(c.getPersonName())
                .similarity(c.getSimilarity())
                .matched(c.getMatchedEmployeeId() != null && !c.getMatchedEmployeeId().isBlank())
                .recognitionSource(c.getRecognitionSource())
                .candidateEmployeeId(c.getCandidateEmployeeId())
                .candidateEmployeeName(c.getCandidateEmployeeName())
                .candidateScore(c.getCandidateScore())
                .matchNote(c.getMatchNote())
                .reviewStatus(c.getReviewStatus() != null ? c.getReviewStatus().name() : null)
                .build();
    }

    private void deleteFileQuietly(String imageUrl) {
        try {
            Files.deleteIfExists(resolveImagePath(imageUrl));
        } catch (IOException e) {
            log.warn("Could not delete capture file {}: {}", imageUrl, e.getMessage());
        }
    }

    /** Map a public /faces/... URL back to a file inside the face storage dir. */
    private Path resolveImagePath(String imageUrl) throws IOException {
        String relative = imageUrl != null && imageUrl.startsWith("/faces/")
                ? imageUrl.substring("/faces/".length())
                : imageUrl;
        if (relative == null || relative.isBlank()) {
            throw new IOException("Empty image path");
        }
        Path base = Paths.get(faceStorageDir).toAbsolutePath().normalize();
        Path resolved = base.resolve(relative).normalize();
        // Never touch files outside the face storage directory (e.g. "../../something").
        if (!resolved.startsWith(base)) {
            throw new IOException("Image path escapes the face storage directory: " + imageUrl);
        }
        return resolved;
    }
}
