package com.attendance.service;

import com.attendance.dto.EmployeeDTO;
import com.attendance.dto.FaceCaptureDTO;
import com.attendance.dto.FaceEvent;
import com.attendance.entity.CameraConfig;
import com.attendance.entity.Employee;
import com.attendance.entity.FaceCapture;
import com.attendance.repository.FaceCaptureRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            Path dir = Paths.get(faceStorageDir, "captures");
            Files.createDirectories(dir);

            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            String filename = camera.getId() + "_" + ts + "_" + ThreadLocalRandom.current().nextInt(1000) + ".jpg";
            Files.write(dir.resolve(filename), picture);

            LocalDateTime now = LocalDateTime.now();
            captureRepository.save(FaceCapture.builder()
                    .cameraId(camera.getId())
                    .cameraName(camera.getCameraName())
                    .imageUrl("/faces/captures/" + filename)
                    .matchedEmployeeId(employee != null ? employee.getEmployeeId() : event.getEmployeeNo())
                    .matchedEmployeeName(employee != null ? employee.getFullName() : null)
                    .personName(event.getPersonName())
                    .similarity(event.getSimilarity())
                    .capturedAt(now)
                    .expiresAt(now.plusHours(retentionHours))
                    .build());
        } catch (IOException e) {
            log.warn("Failed to store captured face: {}", e.getMessage());
        }
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
        List<FaceCapture> expired = captureRepository.findByExpiresAtBefore(LocalDateTime.now());
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
    private Path resolveImagePath(String imageUrl) {
        String relative = imageUrl != null && imageUrl.startsWith("/faces/")
                ? imageUrl.substring("/faces/".length())
                : imageUrl;
        return Paths.get(faceStorageDir).resolve(relative).normalize();
    }
}
