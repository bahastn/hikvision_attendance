package com.attendance.service;

import com.attendance.dto.CameraTargetDTO;
import com.attendance.dto.EmployeeDTO;
import com.attendance.entity.CameraConfig;
import com.attendance.entity.Employee;
import com.attendance.entity.EmployeeCameraTarget;
import com.attendance.repository.CameraConfigRepository;
import com.attendance.repository.EmployeeCameraTargetRepository;
import com.attendance.repository.EmployeeRepository;
import com.attendance.service.insightface.FaceEmbeddingEvents;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Enrolls an employee's face photo into camera face libraries (FDLib) and keeps
 * track of which cameras received the face ({@link EmployeeCameraTarget}), so a
 * face can be sent to or removed from individual cameras. The employee number is
 * sent as the FDLib certificateNumber, which is what the camera reports back in
 * match events.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FaceEnrollmentService {

    private final EmployeeRepository employeeRepository;
    private final CameraConfigRepository cameraRepository;
    private final EmployeeCameraTargetRepository targetRepository;
    private final HikvisionIsapiClient isapiClient;
    private final EmployeeService employeeService;
    private final ApplicationEventPublisher eventPublisher;

    @Value("${attendance.camera.face-lib-id:1}")
    private String faceLibId;

    @Value("${attendance.face-storage-dir:faces}")
    private String faceStorageDir;

    /** Result of an enrollment: the updated employee plus a human-readable status message. */
    public record EnrollmentResult(EmployeeDTO employee, String message) {
    }

    /**
     * Upload the employee's photo to every active camera's face library.
     * The photo is always stored on disk; if no camera accepts it, an exception is
     * thrown so the caller can report the failure (database changes roll back).
     */
    @Transactional
    public EnrollmentResult enrollFace(Long employeeDbId, byte[] photoBytes) {
        return enrollFace(employeeDbId, photoBytes, null);
    }

    /**
     * Upload the employee's photo to the face library of the selected cameras.
     * cameraIds null or empty means every active camera.
     * When photoBytes is null the photo already stored on disk is used
     * (used when re-activating an employee).
     */
    @Transactional
    public EnrollmentResult enrollFace(Long employeeDbId, byte[] photoBytes, List<Long> cameraIds) {
        Employee employee = employeeRepository.findById(employeeDbId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        byte[] jpeg;
        String imageUrl;
        if (photoBytes != null && photoBytes.length > 0) {
            jpeg = toJpeg(photoBytes);
            imageUrl = savePhoto(employee.getId(), jpeg);
        } else {
            jpeg = loadStoredPhoto(employee);
            imageUrl = employee.getFaceImageUrl();
        }

        List<CameraConfig> cameras = (cameraIds == null || cameraIds.isEmpty())
                ? cameraRepository.findByIsActiveTrue()
                : cameraRepository.findAllById(cameraIds).stream()
                        .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                        .collect(Collectors.toList());
        List<String> failures = new ArrayList<>();
        String storedPid = employee.getFaceTemplateId();
        int succeeded = 0;

        for (CameraConfig camera : cameras) {
            try {
                String pid = pushToCamera(employee, camera, jpeg);
                succeeded++;
                if (pid != null && !pid.isBlank()) {
                    storedPid = pid;
                    saveTarget(employee, camera, pid);
                }
            } catch (Exception e) {
                failures.add(camera.getCameraName() + ": " + e.getMessage());
            }
        }

        if (succeeded == 0 && !cameras.isEmpty()) {
            throw new RuntimeException("Camera rejected the photo: " + String.join("; ", failures));
        }

        employee.setFaceImageUrl(imageUrl);
        if (succeeded > 0) {
            employee.setFaceTemplateId(storedPid);
        }
        employeeRepository.save(employee);

        // Regenerate the InsightFace embedding from the just-stored photo (after commit, async;
        // no-op when the feature is off). The photo is on disk whether or not a camera accepted it.
        eventPublisher.publishEvent(new FaceEmbeddingEvents.FaceEnrolled(employee.getId()));

        String message;
        if (cameras.isEmpty()) {
            message = "No active camera selected — the face was not pushed to any camera.";
        } else if (failures.isEmpty()) {
            message = "Face enrolled on " + succeeded + " camera(s)" + (storedPid != null ? " (PID " + storedPid + ")" : "");
        } else {
            message = "Enrolled on " + succeeded + " camera(s); failed on: " + String.join("; ", failures);
        }
        return new EnrollmentResult(employeeService.getEmployeeById(employeeDbId), message);
    }

    /**
     * Push the employee's stored photo to a single camera. Used by the
     * camera-target UI to send a face to one camera at a time.
     */
    @Transactional
    public EnrollmentResult enrollOnCamera(Long employeeDbId, Long cameraId) {
        Employee employee = employeeRepository.findById(employeeDbId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        CameraConfig camera = cameraRepository.findById(cameraId)
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .orElseThrow(() -> new RuntimeException("Camera not found or not active"));

        byte[] jpeg = loadStoredPhoto(employee);
        String pid = pushToCamera(employee, camera, jpeg);
        saveTarget(employee, camera, pid);
        employee.setFaceTemplateId(pid);
        employeeRepository.save(employee);

        // Ensure the InsightFace embedding exists for this employee (after commit, async; no-op when off).
        eventPublisher.publishEvent(new FaceEmbeddingEvents.FaceEnrolled(employee.getId()));

        return new EnrollmentResult(employeeService.getEmployeeById(employeeDbId),
                "Face sent to " + camera.getCameraName() + (pid != null ? " (PID " + pid + ")" : ""));
    }

    /** Remove the employee's face from every camera it was pushed to and clear the local mapping. */
    @Transactional
    public void removeFace(Long employeeDbId) {
        Employee employee = employeeRepository.findById(employeeDbId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        List<EmployeeCameraTarget> targets = targetRepository.findByEmployeeId(employeeDbId);
        if (!targets.isEmpty()) {
            Map<Long, CameraConfig> activeCameras = cameraRepository.findByIsActiveTrue().stream()
                    .collect(Collectors.toMap(CameraConfig::getId, Function.identity()));
            for (EmployeeCameraTarget target : targets) {
                CameraConfig camera = activeCameras.get(target.getCamera().getId());
                if (camera == null) {
                    continue;
                }
                deleteFromCamera(employee, camera, target.getPid());
            }
            targetRepository.deleteByEmployeeId(employeeDbId);
        } else if (employee.getFaceTemplateId() != null && !employee.getFaceTemplateId().isBlank()) {
            // Legacy employee enrolled before per-camera tracking existed:
            // try to delete from every active camera.
            for (CameraConfig camera : cameraRepository.findByIsActiveTrue()) {
                deleteFromCamera(employee, camera, employee.getFaceTemplateId());
            }
        }
        employee.setFaceTemplateId(null);
        employee.setFaceImageUrl(null);
        employeeRepository.save(employee);

        // Drop the InsightFace embedding too (after commit, async; no-op when off).
        eventPublisher.publishEvent(new FaceEmbeddingEvents.FaceRemoved(employeeDbId));
    }

    /** Remove the employee's face from a single camera (camera-target UI). */
    @Transactional
    public void removeFromCamera(Long employeeDbId, Long cameraId) {
        Employee employee = employeeRepository.findById(employeeDbId)
                .orElseThrow(() -> new RuntimeException("Employee not found"));
        CameraConfig camera = cameraRepository.findById(cameraId)
                .orElseThrow(() -> new RuntimeException("Camera not found"));

        String pid = targetRepository.findByEmployeeIdAndCameraId(employeeDbId, cameraId)
                .map(EmployeeCameraTarget::getPid)
                .orElse(null);
        if (pid == null || pid.isBlank()) {
            pid = isapiClient.findFaceByCertificateNumber(camera, faceLibId, employee.getEmployeeId())
                    .map(person -> person.pid)
                    .orElse(employee.getFaceTemplateId());
        }
        if (pid != null && !pid.isBlank()) {
            HikvisionIsapiClient.FaceLibResult result = isapiClient.deleteFaceFromLibrary(camera, faceLibId, pid);
            if (!result.success) {
                log.warn("Deleting face PID {} on {} failed: {}", pid, camera.getIpAddress(), result.message);
            }
        }
        targetRepository.deleteByEmployeeIdAndCameraId(employeeDbId, cameraId);

        if (targetRepository.findByEmployeeId(employeeDbId).isEmpty()) {
            employee.setFaceTemplateId(null);
            employeeRepository.save(employee);
        }
    }

    /** Every active camera with the enrollment state of this employee's face on it. */
    @Transactional(readOnly = true)
    public List<CameraTargetDTO> getCameraTargets(Long employeeDbId) {
        if (!employeeRepository.existsById(employeeDbId)) {
            throw new RuntimeException("Employee not found");
        }
        Map<Long, EmployeeCameraTarget> byCameraId = targetRepository.findByEmployeeId(employeeDbId).stream()
                .collect(Collectors.toMap(t -> t.getCamera().getId(), Function.identity()));
        return cameraRepository.findByIsActiveTrue().stream()
                .map(cam -> {
                    EmployeeCameraTarget target = byCameraId.get(cam.getId());
                    return CameraTargetDTO.builder()
                            .cameraId(cam.getId())
                            .cameraName(cam.getCameraName())
                            .location(cam.getLocation())
                            .enrolled(target != null)
                            .pid(target != null ? target.getPid() : null)
                            .enrolledAt(target != null && target.getEnrolledAt() != null
                                    ? target.getEnrolledAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                                    : null)
                            .build();
                })
                .collect(Collectors.toList());
    }

    /**
     * Add or replace the employee's face on one camera. Looks the person up by
     * certificateNumber (= employee ID) first so re-sending updates the existing
     * entry instead of duplicating it. Returns the camera-local PID, or null if
     * the camera did not assign one.
     */
    private String pushToCamera(Employee employee, CameraConfig camera, byte[] jpeg) {
        String pid = isapiClient
                .findFaceByCertificateNumber(camera, faceLibId, employee.getEmployeeId())
                .map(person -> person.pid)
                .orElse(null);
        if (pid == null || pid.isBlank()) {
            pid = employee.getFaceTemplateId();
        }

        if (pid != null && !pid.isBlank()) {
            // Already enrolled: replace data + photo. If the camera rejects the
            // replace, delete the old entry and add it again.
            HikvisionIsapiClient.FaceLibResult updated = isapiClient.updateFaceInLibrary(
                    camera, faceLibId, pid, employee.getFullName(), employee.getEmployeeId(), jpeg);
            if (updated.success) {
                return pid;
            }
            log.info("Replacing face on {} failed ({}), re-adding", camera.getIpAddress(), updated.message);
            isapiClient.deleteFaceFromLibrary(camera, faceLibId, pid);
        }
        HikvisionIsapiClient.FaceLibResult added = isapiClient.addFaceToLibrary(
                camera, faceLibId, employee.getFullName(), employee.getEmployeeId(), jpeg);
        if (added.success) {
            return added.pid != null ? added.pid : pid;
        }
        throw new RuntimeException(added.message);
    }

    /** Best-effort face deletion on one camera; failures are logged, not thrown. */
    private void deleteFromCamera(Employee employee, CameraConfig camera, String pid) {
        String resolved = (pid != null && !pid.isBlank()) ? pid
                : isapiClient.findFaceByCertificateNumber(camera, faceLibId, employee.getEmployeeId())
                        .map(person -> person.pid)
                        .orElse(employee.getFaceTemplateId());
        if (resolved == null || resolved.isBlank()) {
            return;
        }
        try {
            HikvisionIsapiClient.FaceLibResult result =
                    isapiClient.deleteFaceFromLibrary(camera, faceLibId, resolved);
            if (!result.success) {
                log.warn("Deleting face PID {} on {} failed: {}", resolved, camera.getIpAddress(), result.message);
            }
        } catch (Exception e) {
            log.warn("Deleting face PID {} on {} failed: {}", resolved, camera.getIpAddress(), e.getMessage());
        }
    }

    /** Record (or refresh) that the employee's face now lives on this camera. */
    private void saveTarget(Employee employee, CameraConfig camera, String pid) {
        EmployeeCameraTarget target = targetRepository
                .findByEmployeeIdAndCameraId(employee.getId(), camera.getId())
                .orElse(null);
        if (target == null) {
            target = EmployeeCameraTarget.builder()
                    .employee(employee)
                    .camera(camera)
                    .build();
        }
        target.setPid(pid);
        target.setEnrolledAt(LocalDateTime.now());
        targetRepository.save(target);
    }

    /** The employee's stored face photo; throws when no photo was uploaded yet. */
    private byte[] loadStoredPhoto(Employee employee) {
        Path path = Paths.get(faceStorageDir, "enrolled", "employee_" + employee.getId() + ".jpg");
        if (!Files.exists(path)) {
            throw new RuntimeException("No photo on file for this employee — edit the employee and upload a photo first");
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new RuntimeException("Could not read the stored photo: " + e.getMessage());
        }
    }

    /** True when the bytes already look like a JPEG; otherwise converts any readable image to JPEG. */
    private byte[] toJpeg(byte[] bytes) {
        if (bytes == null || bytes.length < 4) {
            throw new RuntimeException("Photo file is empty");
        }
        boolean jpegMagic = (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF;
        if (jpegMagic) {
            return bytes;
        }
        try {
            BufferedImage read = ImageIO.read(new ByteArrayInputStream(bytes));
            if (read == null) {
                throw new RuntimeException("Unsupported photo format — please upload a JPEG image");
            }
            BufferedImage rgb = new BufferedImage(read.getWidth(), read.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, read.getWidth(), read.getHeight());
            g.drawImage(read, 0, 0, null);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(rgb, "jpg", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Could not read the photo — please upload a JPEG image");
        }
    }

    private String savePhoto(Long employeeDbId, byte[] jpeg) {
        try {
            Path dir = Paths.get(faceStorageDir, "enrolled");
            Files.createDirectories(dir);
            String filename = "employee_" + employeeDbId + ".jpg";
            Files.write(dir.resolve(filename), jpeg);
            return "/faces/enrolled/" + filename;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store the photo on disk: " + e.getMessage());
        }
    }
}
