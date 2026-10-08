package com.attendance.service;

import com.attendance.dto.FaceEvent;
import com.attendance.entity.CameraConfig;
import com.attendance.entity.Employee;
import com.attendance.repository.CameraConfigRepository;
import com.attendance.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

/**
 * Periodically polls every active Hikvision camera for new face events,
 * downloads the captured face picture, maps the camera's employee number to
 * an {@link Employee} and records attendance.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CameraEventPoller {

    private final CameraConfigRepository cameraRepository;
    private final EmployeeRepository employeeRepository;
    private final HikvisionIsapiClient isapiClient;
    private final AttendanceService attendanceService;

    @Value("${attendance.face-storage-dir:faces}")
    private String faceStorageDir;

    @Value("${attendance.initial-lookback-minutes:5}")
    private int initialLookbackMinutes;

    @Value("${attendance.similarity.threshold:80}")
    private double similarityThreshold;

    /** Poll all active cameras every 2 seconds (aligned with the UI sync interval). */
    @Scheduled(fixedRateString = "${attendance.camera.poll-interval:2000}")
    public void pollCameras() {
        List<CameraConfig> cameras = cameraRepository.findByIsActiveTrue();
        for (CameraConfig camera : cameras) {
            try {
                pollCamera(camera);
            } catch (Exception e) {
                log.warn("Error polling camera {}: {}", camera.getIpAddress(), e.getMessage());
                markDisconnected(camera);
            }
        }
    }

    private void pollCamera(CameraConfig camera) {
        // First verify the camera is actually reachable with the stored credentials
        if (!isapiClient.isReachable(camera)) {
            markDisconnected(camera);
            return;
        }
        markConnected(camera);

        List<FaceEvent> events = isapiClient.fetchNewFaceEvents(camera, initialLookbackMinutes);
        if (events.isEmpty()) {
            return;
        }

        log.info("Camera {} returned {} face event(s)", camera.getIpAddress(), events.size());

        for (FaceEvent event : events) {
            try {
                handleEvent(camera, event);
            } catch (Exception e) {
                log.warn("Failed to handle face event from {}: {}", camera.getIpAddress(), e.getMessage());
            }
        }
    }

    private void handleEvent(CameraConfig camera, FaceEvent event) {
        // Resolve the employee from the camera's employee number.
        Optional<Employee> employeeOpt = resolveEmployee(event);

        // Download the face picture (works for both known and unknown faces).
        String storedImagePath = null;
        if (event.getPictureUrl() != null && !event.getPictureUrl().isBlank()) {
            storedImagePath = isapiClient
                    .downloadPicture(camera, event.getPictureUrl())
                    .map(bytes -> saveFaceImage(camera, event, bytes))
                    .orElse(null);
        }

        if (employeeOpt.isEmpty()) {
            log.info("Face event from {} did not match any employee (employeeNo={}, name={})",
                    camera.getIpAddress(), event.getEmployeeNo(), event.getPersonName());
            return;
        }

        Employee employee = employeeOpt.get();

        // Skip low-similarity recognitions.
        if (event.getSimilarity() != null && event.getSimilarity() < similarityThreshold) {
            log.info("Skipping low-similarity detection for {} ({}% < {}%)",
                    employee.getEmployeeId(), event.getSimilarity(), similarityThreshold);
            return;
        }

        String deviceId = event.getDeviceId() != null ? event.getDeviceId() : camera.getCameraName();
        String direction = determineDirection(employee.getId());

        attendanceService.processDetection(
                employee.getEmployeeId(),
                deviceId,
                event.getSimilarity(),
                direction,
                storedImagePath);
    }

    /**
     * Map the camera's employee number to an Employee.
     * Tries faceTemplateId first, then employeeId, then the camera-reported name.
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
                    .filter(e -> name.equalsIgnoreCase(e.getFullName()))
                    .findFirst();
        }
        return Optional.empty();
    }

    /**
     * First detection of the day is a check-IN; a later detection is a check-OUT.
     */
    private String determineDirection(Long employeeId) {
        boolean alreadyCheckedInToday = attendanceService.hasOpenCheckInToday(employeeId);
        return alreadyCheckedInToday ? "OUT" : "IN";
    }

    /** Save the downloaded JPEG to disk and return the public URL path. */
    private String saveFaceImage(CameraConfig camera, FaceEvent event, byte[] bytes) {
        try {
            Path dir = Paths.get(faceStorageDir);
            Files.createDirectories(dir);

            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            String filename = camera.getId() + "_" + ts + ".jpg";
            Path file = dir.resolve(filename);
            Files.write(file, bytes, StandardOpenOption.CREATE);

            return "/faces/" + filename;
        } catch (IOException e) {
            log.warn("Failed to save face image: {}", e.getMessage());
            return null;
        }
    }

    private void markConnected(CameraConfig camera) {
        if (!Boolean.TRUE.equals(camera.getIsConnected())) {
            camera.setIsConnected(true);
            camera.setLastSync(LocalDateTime.now());
            cameraRepository.save(camera);
        } else {
            camera.setLastSync(LocalDateTime.now());
            cameraRepository.save(camera);
        }
    }

    private void markDisconnected(CameraConfig camera) {
        if (Boolean.TRUE.equals(camera.getIsConnected())) {
            camera.setIsConnected(false);
            cameraRepository.save(camera);
        }
    }
}
