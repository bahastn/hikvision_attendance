package com.attendance.service;

import com.attendance.entity.CameraConfig;
import com.attendance.entity.Employee;
import com.attendance.entity.EmployeeCameraTarget;
import com.attendance.repository.CameraConfigRepository;
import com.attendance.repository.EmployeeCameraTargetRepository;
import com.attendance.repository.EmployeeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Synchronises the face library stored on a camera (FDLib) with the application database.
 *
 * <p>Sync directions:
 * <ul>
 *   <li><b>Import</b> — people on the camera that are not in the DB become new employees
 *       (their face photo is downloaded from the camera when available).</li>
 *   <li><b>Link</b>   — people on the camera whose certificateNumber matches an existing
 *       employee get their PID recorded in {@link EmployeeCameraTarget}.</li>
 *   <li><b>Clean</b>  — people on the camera that match a <i>deactivated</i> or deleted
 *       employee are removed from the camera face library.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CameraFaceLibraryService {

    private final CameraConfigRepository cameraRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeCameraTargetRepository targetRepository;
    private final HikvisionIsapiClient isapiClient;

    @Value("${attendance.camera.face-lib-id:1}")
    private String faceLibId;

    @Value("${attendance.face-storage-dir:faces}")
    private String faceStorageDir;

    // ------------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------------

    /** One person as reported by the camera's FDLib. */
    public record CameraFacePerson(String pid, String name, String certificateNumber,
                                   boolean inDatabase, Long employeeDbId, String employeeName,
                                   boolean employeeActive) {
    }

    /** Result of a sync operation. */
    public record SyncResult(int imported, int linked, int removed, int skipped, List<String> messages) {
    }

    // ------------------------------------------------------------------
    // Read camera face library
    // ------------------------------------------------------------------

    /**
     * List every person in the camera's face library, annotated with whether
     * they exist in the application database.
     */
    @Transactional(readOnly = true)
    public List<CameraFacePerson> getCameraFaceLibrary(Long cameraId) {
        CameraConfig camera = getActiveCamera(cameraId);
        List<HikvisionIsapiClient.FaceLibPerson> people = isapiClient.searchFaceLibrary(camera, faceLibId, 500);
        Map<String, Employee> byEmployeeId = employeeRepository.findAll().stream()
                .collect(Collectors.toMap(Employee::getEmployeeId, Function.identity(), (a, b) -> a));

        List<CameraFacePerson> result = new ArrayList<>();
        for (HikvisionIsapiClient.FaceLibPerson p : people) {
            Employee match = (p.certificateNumber != null && !p.certificateNumber.isBlank())
                    ? byEmployeeId.get(p.certificateNumber)
                    : null;
            result.add(new CameraFacePerson(
                    p.pid, p.name, p.certificateNumber,
                    match != null,
                    match != null ? match.getId() : null,
                    match != null ? match.getFullName() : null,
                    match != null && Boolean.TRUE.equals(match.getIsActive())));
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Full sync
    // ------------------------------------------------------------------

    /**
     * Synchronise one camera's face library with the database.
     *
     * <ul>
     *   <li>Camera people whose certificateNumber is not in the DB → imported as new employees.</li>
     *   <li>Camera people whose certificateNumber matches a DB employee → PID linked in
     *       {@link EmployeeCameraTarget}, name updated if different.</li>
     *   <li>Camera people whose certificateNumber matches a <i>deactivated</i> employee →
     *       removed from the camera.</li>
     * </ul>
     */
    @Transactional
    public SyncResult syncCameraFaceLibrary(Long cameraId) {
        CameraConfig camera = getActiveCamera(cameraId);
        List<HikvisionIsapiClient.FaceLibPerson> cameraPeople = isapiClient.searchFaceLibrary(camera, faceLibId, 500);
        if (cameraPeople.isEmpty()) {
            return new SyncResult(0, 0, 0, 0, List.of("Camera face library is empty or unreachable"));
        }

        Map<String, Employee> byEmployeeId = employeeRepository.findAll().stream()
                .collect(Collectors.toMap(Employee::getEmployeeId, Function.identity(), (a, b) -> a));

        int imported = 0, linked = 0, removed = 0, skipped = 0;
        List<String> messages = new ArrayList<>();

        for (HikvisionIsapiClient.FaceLibPerson person : cameraPeople) {
            String cert = person.certificateNumber;
            if (cert == null || cert.isBlank()) {
                skipped++;
                messages.add("Skipped PID " + person.pid + " (" + person.name + ") — no certificate number");
                continue;
            }

            Employee employee = byEmployeeId.get(cert);

            if (employee == null) {
                // ---- Import as new employee ----
                try {
                    employee = importPerson(camera, person);
                    byEmployeeId.put(cert, employee);
                    imported++;
                    messages.add("Imported " + person.name + " (" + cert + ") as employee #" + employee.getId());
                } catch (Exception e) {
                    skipped++;
                    messages.add("Failed to import " + person.name + " (" + cert + "): " + e.getMessage());
                    log.warn("Import of {} from camera {} failed", cert, camera.getIpAddress(), e);
                }
            } else if (!Boolean.TRUE.equals(employee.getIsActive())) {
                // ---- Deactivated in app → remove from camera ----
                HikvisionIsapiClient.FaceLibResult del = isapiClient.deleteFaceFromLibrary(camera, faceLibId, person.pid);
                if (del.success) {
                    removed++;
                    messages.add("Removed " + person.name + " (" + cert + ") from camera — employee is deactivated");
                    targetRepository.deleteByEmployeeIdAndCameraId(employee.getId(), cameraId);
                } else {
                    skipped++;
                    messages.add("Could not remove " + person.name + " (" + cert + "): " + del.message);
                }
            } else {
                // ---- Link / update existing employee ----
                boolean changed = false;
                if (employee.getFullName() == null || !employee.getFullName().equals(person.name)) {
                    employee.setFullName(person.name);
                    changed = true;
                }
                if (employee.getFaceTemplateId() == null || employee.getFaceTemplateId().isBlank()) {
                    employee.setFaceTemplateId(person.pid);
                    changed = true;
                }
                if (changed) {
                    employeeRepository.save(employee);
                }
                saveTarget(employee, camera, person.pid);
                linked++;
            }
        }

        camera.setLastSync(LocalDateTime.now());
        cameraRepository.save(camera);

        log.info("Face library sync on {}: {} imported, {} linked, {} removed, {} skipped",
                camera.getCameraName(), imported, linked, removed, skipped);
        return new SyncResult(imported, linked, removed, skipped, messages);
    }

    /**
     * Sync <em>every</em> active camera. Returns a map of camera name → sync result.
     */
    @Transactional
    public Map<String, SyncResult> syncAllCameras() {
        Map<String, SyncResult> results = new LinkedHashMap<>();
        for (CameraConfig camera : cameraRepository.findByIsActiveTrue()) {
            try {
                results.put(camera.getCameraName(), syncCameraFaceLibrary(camera.getId()));
            } catch (Exception e) {
                results.put(camera.getCameraName(),
                        new SyncResult(0, 0, 0, 0, List.of("Sync failed: " + e.getMessage())));
                log.warn("Face library sync failed for camera {}: {}", camera.getCameraName(), e.getMessage());
            }
        }
        return results;
    }

    // ------------------------------------------------------------------
    // Delete person from camera
    // ------------------------------------------------------------------

    /** Remove one person from the camera's face library by PID. */
    @Transactional
    public boolean deletePersonFromCamera(Long cameraId, String pid) {
        CameraConfig camera = getActiveCamera(cameraId);
        HikvisionIsapiClient.FaceLibResult result = isapiClient.deleteFaceFromLibrary(camera, faceLibId, pid);
        if (result.success) {
            targetRepository.findAll().stream()
                    .filter(t -> t.getCamera().getId().equals(cameraId) && pid.equals(t.getPid()))
                    .forEach(t -> targetRepository.delete(t));
        }
        return result.success;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private CameraConfig getActiveCamera(Long cameraId) {
        return cameraRepository.findById(cameraId)
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .orElseThrow(() -> new RuntimeException("Camera not found or not active"));
    }

    /**
     * Create a new employee from a camera FDLib person. Downloads the face photo
     * from the camera when the firmware supports it.
     */
    private Employee importPerson(CameraConfig camera, HikvisionIsapiClient.FaceLibPerson person) {
        // Face photo (best-effort — the employee is created even without it)
        String faceImageUrl = null;
        Optional<byte[]> photo = isapiClient.downloadFacePicture(camera, faceLibId, person.pid);
        if (photo.isPresent()) {
            faceImageUrl = saveImportedPhoto(person.certificateNumber, photo.get());
        }

        Employee employee = Employee.builder()
                .employeeId(person.certificateNumber)
                .fullName(person.name != null && !person.name.isBlank() ? person.name : person.certificateNumber)
                .faceTemplateId(person.pid)
                .faceImageUrl(faceImageUrl)
                .isActive(true)
                .build();
        employee = employeeRepository.save(employee);

        saveTarget(employee, camera, person.pid);
        return employee;
    }

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

    private String saveImportedPhoto(String certificateNumber, byte[] jpeg) {
        try {
            Path dir = Paths.get(faceStorageDir, "enrolled");
            Files.createDirectories(dir);
            String safeName = certificateNumber.replaceAll("[^a-zA-Z0-9_-]", "_");
            String filename = "imported_" + safeName + ".jpg";
            Files.write(dir.resolve(filename), jpeg);
            return "/faces/enrolled/" + filename;
        } catch (IOException e) {
            log.warn("Could not save imported face photo for {}: {}", certificateNumber, e.getMessage());
            return null;
        }
    }
}
