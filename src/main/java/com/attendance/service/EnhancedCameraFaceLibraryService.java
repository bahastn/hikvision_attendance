package com.attendance.service;

import com.attendance.dto.ImportPreview;
import com.attendance.entity.*;
import com.attendance.repository.*;
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
 * Enhanced service for synchronizing camera face libraries with the database.
 * Adds preview, selective sync, conflict detection, and audit trail.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EnhancedCameraFaceLibraryService {

    private final CameraConfigRepository cameraRepository;
    private final EmployeeRepository employeeRepository;
    private final EmployeeCameraTargetRepository targetRepository;
    private final SyncHistoryRepository syncHistoryRepository;
    private final HikvisionIsapiClient isapiClient;

    @Value("${attendance.camera.face-lib-id:1}")
    private String faceLibId;

    @Value("${attendance.face-storage-dir:faces}")
    private String faceStorageDir;

    /** Max people pulled from the camera face library in one sync (single FDSearch page). */
    @Value("${attendance.camera.face-lib-max-results:5000}")
    private int faceLibMaxResults;

    /**
     * Whether to download each imported person's face photo from the camera during sync. This is
     * the slowest part of a large sync (one authenticated HTTP download per new person), so it is
     * OFF by default: the camera already holds the face, and the app thumbnail can be backfilled
     * later. Turn on only for small libraries where the thumbnail is wanted immediately.
     */
    @Value("${attendance.camera.sync-download-photos:false}")
    private boolean downloadPhotosOnSync;

    // ------------------------------------------------------------------
    // Preview Operations
    // ------------------------------------------------------------------

    /**
     * Generate a preview of what will happen during sync without making changes.
     */
    @Transactional(readOnly = true)
    public ImportPreview previewSync(Long cameraId) {
        CameraConfig camera = getActiveCamera(cameraId);
        List<HikvisionIsapiClient.FaceLibPerson> cameraPeople = isapiClient.searchFaceLibrary(camera, faceLibId, faceLibMaxResults);
        
        Map<String, Employee> byEmployeeId = employeeRepository.findAll().stream()
                .collect(Collectors.toMap(Employee::getEmployeeId, Function.identity(), (a, b) -> a));

        List<ImportPreview.CameraFacePersonDTO> newPeople = new ArrayList<>();
        List<ImportPreview.CameraFacePersonDTO> existingPeople = new ArrayList<>();
        List<ImportPreview.CameraFacePersonDTO> conflicts = new ArrayList<>();
        List<ImportPreview.CameraFacePersonDTO> deactivated = new ArrayList<>();
        List<ImportPreview.CameraFacePersonDTO> noCertificate = new ArrayList<>();

        for (HikvisionIsapiClient.FaceLibPerson p : cameraPeople) {
            ImportPreview.CameraFacePersonDTO dto = ImportPreview.CameraFacePersonDTO.builder()
                    .pid(p.pid)
                    .name(p.name)
                    .certificateNumber(p.certificateNumber)
                    .selected(true) // Default to selected
                    .build();

            if (p.certificateNumber == null || p.certificateNumber.isBlank()) {
                dto.setSuggestedAction("SKIP - No certificate number");
                noCertificate.add(dto);
                continue;
            }

            Employee match = byEmployeeId.get(p.certificateNumber);
            dto.setInDatabase(match != null);
            
            if (match == null) {
                dto.setSuggestedAction("IMPORT - Create new employee");
                newPeople.add(dto);
            } else {
                dto.setEmployeeDbId(match.getId());
                dto.setEmployeeName(match.getFullName());
                dto.setEmployeeActive(Boolean.TRUE.equals(match.getIsActive()));
                
                if (!Boolean.TRUE.equals(match.getIsActive())) {
                    dto.setSuggestedAction("REMOVE - Employee is deactivated");
                    deactivated.add(dto);
                } else if (!Objects.equals(match.getFullName(), p.name)) {
                    dto.setConflictReason("Name mismatch: Camera='" + p.name + "' vs DB='" + match.getFullName() + "'");
                    dto.setSuggestedAction("UPDATE - Sync name from camera");
                    conflicts.add(dto);
                } else {
                    dto.setSuggestedAction("LINK - Update PID mapping");
                    existingPeople.add(dto);
                }
            }
        }

        return ImportPreview.builder()
                .cameraId(cameraId)
                .cameraName(camera.getCameraName())
                .previewTime(LocalDateTime.now())
                .newPeople(newPeople)
                .existingPeople(existingPeople)
                .conflicts(conflicts)
                .deactivated(deactivated)
                .noCertificate(noCertificate)
                .totalOnCamera(cameraPeople.size())
                .totalNew(newPeople.size())
                .totalExisting(existingPeople.size())
                .totalConflicts(conflicts.size())
                .totalDeactivated(deactivated.size())
                .totalNoCertificate(noCertificate.size())
                .build();
    }

    // ------------------------------------------------------------------
    // Selective Sync
    // ------------------------------------------------------------------

    /**
     * Sync only selected people from the camera.
     */
    @Transactional
    public SyncResult selectiveSync(Long cameraId, List<String> selectedPids, 
                                     Map<String, String> pidToEmployeeIdMap,
                                     String performedBy) {
        CameraConfig camera = getActiveCamera(cameraId);
        String batchId = UUID.randomUUID().toString();
        
        List<HikvisionIsapiClient.FaceLibPerson> cameraPeople = isapiClient.searchFaceLibrary(camera, faceLibId, faceLibMaxResults);
        Map<String, Employee> byEmployeeId = employeeRepository.findAll().stream()
                .collect(Collectors.toMap(Employee::getEmployeeId, Function.identity(), (a, b) -> a));

        int imported = 0, linked = 0, removed = 0, skipped = 0, updated = 0;
        List<String> messages = new ArrayList<>();

        for (HikvisionIsapiClient.FaceLibPerson person : cameraPeople) {
            if (!selectedPids.contains(person.pid)) {
                continue; // Skip not selected
            }

            String cert = person.certificateNumber;
            if (cert == null || cert.isBlank()) {
                skipped++;
                messages.add("Skipped PID " + person.pid + " - no certificate number");
                continue;
            }

            // Check for custom employee ID mapping
            String targetEmployeeId = pidToEmployeeIdMap.getOrDefault(person.pid, cert);
            Employee employee = byEmployeeId.get(targetEmployeeId);

            try {
                if (employee == null) {
                    // Import as new employee
                    employee = importPerson(camera, person, targetEmployeeId, performedBy, batchId);
                    imported++;
                    messages.add("Imported " + person.name + " as employee " + employee.getEmployeeId());
                } else if (!Boolean.TRUE.equals(employee.getIsActive())) {
                    // Remove from camera
                    removeFromCamera(camera, person, employee, performedBy, batchId);
                    removed++;
                    messages.add("Removed " + person.name + " from camera (deactivated)");
                } else {
                    // Link/update existing
                    boolean changed = linkPerson(camera, person, employee, performedBy, batchId);
                    if (changed) {
                        updated++;
                        messages.add("Updated " + person.name);
                    } else {
                        linked++;
                        messages.add("Linked " + person.name);
                    }
                }
            } catch (Exception e) {
                skipped++;
                messages.add("Error processing " + person.name + ": " + e.getMessage());
                log.error("Error syncing person {} from camera {}", person.pid, cameraId, e);
            }
        }

        camera.setLastSync(LocalDateTime.now());
        cameraRepository.save(camera);

        return new SyncResult(imported, linked, removed, skipped, updated, messages, batchId);
    }

    // ------------------------------------------------------------------
    // Full Sync with Audit
    // ------------------------------------------------------------------

    /**
     * Perform full sync with complete audit trail.
     */
    @Transactional
    public SyncResult syncCameraFaceLibrary(Long cameraId, String performedBy) {
        CameraConfig camera = getActiveCamera(cameraId);
        String batchId = UUID.randomUUID().toString();
        
        List<HikvisionIsapiClient.FaceLibPerson> cameraPeople = isapiClient.searchFaceLibrary(camera, faceLibId, faceLibMaxResults);
        if (cameraPeople.isEmpty()) {
            return new SyncResult(0, 0, 0, 0, 0, List.of("Camera face library is empty"), batchId);
        }

        Map<String, Employee> byEmployeeId = employeeRepository.findAll().stream()
                .collect(Collectors.toMap(Employee::getEmployeeId, Function.identity(), (a, b) -> a));

        int imported = 0, linked = 0, removed = 0, skipped = 0, updated = 0;
        List<String> messages = new ArrayList<>();

        for (HikvisionIsapiClient.FaceLibPerson person : cameraPeople) {
            String cert = person.certificateNumber;
            if (cert == null || cert.isBlank()) {
                skipped++;
                messages.add("Skipped PID " + person.pid + " - no certificate number");
                continue;
            }

            Employee employee = byEmployeeId.get(cert);

            try {
                if (employee == null) {
                    employee = importPerson(camera, person, cert, performedBy, batchId);
                    imported++;
                    messages.add("Imported " + person.name + " (" + cert + ")");
                } else if (!Boolean.TRUE.equals(employee.getIsActive())) {
                    removeFromCamera(camera, person, employee, performedBy, batchId);
                    removed++;
                    messages.add("Removed " + person.name + " (deactivated)");
                } else {
                    boolean changed = linkPerson(camera, person, employee, performedBy, batchId);
                    if (changed) {
                        updated++;
                        messages.add("Updated " + person.name);
                    } else {
                        linked++;
                    }
                }
            } catch (Exception e) {
                skipped++;
                messages.add("Error: " + person.name + " - " + e.getMessage());
            }
        }

        camera.setLastSync(LocalDateTime.now());
        cameraRepository.save(camera);

        log.info("Face library sync on {}: {} imported, {} linked, {} updated, {} removed, {} skipped",
                camera.getCameraName(), imported, linked, updated, removed, skipped);
        
        return new SyncResult(imported, linked, removed, skipped, updated, messages, batchId);
    }

    // ------------------------------------------------------------------
    // Sync All Cameras
    // ------------------------------------------------------------------

    @Transactional
    public Map<String, SyncResult> syncAllCameras(String performedBy) {
        Map<String, SyncResult> results = new LinkedHashMap<>();
        for (CameraConfig camera : cameraRepository.findByIsActiveTrue()) {
            try {
                results.put(camera.getCameraName(), syncCameraFaceLibrary(camera.getId(), performedBy));
            } catch (Exception e) {
                results.put(camera.getCameraName(),
                        new SyncResult(0, 0, 0, 0, 0, List.of("Sync failed: " + e.getMessage()), null));
            }
        }
        return results;
    }

    // ------------------------------------------------------------------
    // Conflict Resolution
    // ------------------------------------------------------------------

    /**
     * Resolve a conflict between camera and database data.
     */
    @Transactional
    public void resolveConflict(Long cameraId, String pid, String resolution, String performedBy) {
        CameraConfig camera = getActiveCamera(cameraId);
        List<HikvisionIsapiClient.FaceLibPerson> people = isapiClient.searchFaceLibrary(camera, faceLibId, faceLibMaxResults);
        
        HikvisionIsapiClient.FaceLibPerson person = people.stream()
                .filter(p -> p.pid.equals(pid))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Person not found on camera"));

        Employee employee = employeeRepository.findByEmployeeId(person.certificateNumber)
                .orElseThrow(() -> new RuntimeException("Employee not found"));

        String batchId = UUID.randomUUID().toString();
        
        switch (resolution.toUpperCase()) {
            case "USE_CAMERA" -> {
                String oldName = employee.getFullName();
                employee.setFullName(person.name);
                employeeRepository.save(employee);
                recordSyncHistory(camera, employee, SyncHistory.SyncOperation.UPDATE, 
                        performedBy, batchId, SyncHistory.SyncResultStatus.SUCCESS,
                        "name", oldName, person.name);
            }
            case "USE_DATABASE" -> {
                // Update camera with database name
                HikvisionIsapiClient.FaceLibResult result = isapiClient.updateFaceInLibrary(
                        camera, faceLibId, pid, employee.getFullName(), employee.getEmployeeId(), null);
                if (!result.success) {
                    throw new RuntimeException("Failed to update camera: " + result.message);
                }
                recordSyncHistory(camera, employee, SyncHistory.SyncOperation.UPDATE,
                        performedBy, batchId, SyncHistory.SyncResultStatus.SUCCESS,
                        "name", person.name, employee.getFullName());
            }
            case "MERGE" -> {
                // Keep both names - update employee with camera name as alias
                // This would require an alias field in Employee entity
                recordSyncHistory(camera, employee, SyncHistory.SyncOperation.UPDATE,
                        performedBy, batchId, SyncHistory.SyncResultStatus.SKIPPED,
                        "merge", null, "Manual review required");
            }
        }
    }

    // ------------------------------------------------------------------
    // Helper Methods
    // ------------------------------------------------------------------

    private CameraConfig getActiveCamera(Long cameraId) {
        return cameraRepository.findById(cameraId)
                .filter(c -> Boolean.TRUE.equals(c.getIsActive()))
                .orElseThrow(() -> new RuntimeException("Camera not found or not active"));
    }

    private Employee importPerson(CameraConfig camera, HikvisionIsapiClient.FaceLibPerson person,
                                   String employeeId, String performedBy, String batchId) {
        // Photo download is the slow part of a large sync; skip it unless explicitly enabled.
        String faceImageUrl = null;
        if (downloadPhotosOnSync) {
            Optional<byte[]> photo = isapiClient.downloadFacePicture(camera, faceLibId, person.pid);
            if (photo.isPresent()) {
                faceImageUrl = saveImportedPhoto(employeeId, photo.get());
            }
        }

        Employee employee = Employee.builder()
                .employeeId(employeeId)
                .fullName(person.name != null && !person.name.isBlank() ? person.name : employeeId)
                .faceTemplateId(person.pid)
                .faceImageUrl(faceImageUrl)
                .isActive(true)
                .syncStatus(SyncStatus.SYNCED)
                .lastCameraSync(LocalDateTime.now())
                .cameraSyncCount(1)
                .build();
        employee = employeeRepository.save(employee);

        saveTarget(employee, camera, person.pid);
        
        recordSyncHistory(camera, employee, SyncHistory.SyncOperation.IMPORT,
                performedBy, batchId, SyncHistory.SyncResultStatus.SUCCESS,
                null, null, "Imported from camera");
        
        return employee;
    }

    private void removeFromCamera(CameraConfig camera, HikvisionIsapiClient.FaceLibPerson person,
                                   Employee employee, String performedBy, String batchId) {
        HikvisionIsapiClient.FaceLibResult del = isapiClient.deleteFaceFromLibrary(camera, faceLibId, person.pid);
        if (del.success) {
            targetRepository.deleteByEmployeeIdAndCameraId(employee.getId(), camera.getId());
            recordSyncHistory(camera, employee, SyncHistory.SyncOperation.DELETE,
                    performedBy, batchId, SyncHistory.SyncResultStatus.SUCCESS,
                    null, person.pid, "Removed from camera - employee deactivated");
        } else {
            throw new RuntimeException("Failed to delete from camera: " + del.message);
        }
    }

    private boolean linkPerson(CameraConfig camera, HikvisionIsapiClient.FaceLibPerson person,
                                Employee employee, String performedBy, String batchId) {
        boolean changed = false;
        StringBuilder changes = new StringBuilder();
        
        if (!Objects.equals(employee.getFullName(), person.name)) {
            String oldName = employee.getFullName();
            employee.setFullName(person.name);
            changed = true;
            changes.append("name: '").append(oldName).append("' -> '").append(person.name).append("'; ");
        }
        
        if (employee.getFaceTemplateId() == null || !employee.getFaceTemplateId().equals(person.pid)) {
            employee.setFaceTemplateId(person.pid);
            changed = true;
            changes.append("pid updated; ");
        }
        
        employee.setSyncStatus(SyncStatus.SYNCED);
        employee.setLastCameraSync(LocalDateTime.now());
        employee.setCameraSyncCount((employee.getCameraSyncCount() != null ? employee.getCameraSyncCount() : 0) + 1);
        
        if (changed) {
            employeeRepository.save(employee);
            recordSyncHistory(camera, employee, SyncHistory.SyncOperation.UPDATE,
                    performedBy, batchId, SyncHistory.SyncResultStatus.SUCCESS,
                    null, null, changes.toString());
        }
        
        saveTarget(employee, camera, person.pid);
        return changed;
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

    private void recordSyncHistory(CameraConfig camera, Employee employee, 
                                    SyncHistory.SyncOperation operation,
                                    String performedBy, String batchId,
                                    SyncHistory.SyncResultStatus status,
                                    String fieldName, String oldValue, String newValue) {
        SyncHistory history = SyncHistory.builder()
                .camera(camera)
                .operation(operation)
                .employeeId(employee != null ? employee.getId() : null)
                .employeeNumber(employee != null ? employee.getEmployeeId() : null)
                .performedBy(performedBy)
                .status(status)
                .syncBatchId(batchId)
                .previousValue(oldValue)
                .newValue(newValue)
                .build();
        syncHistoryRepository.save(history);
    }

    // ------------------------------------------------------------------
    // Result Record
    // ------------------------------------------------------------------

    public record SyncResult(int imported, int linked, int removed, int skipped, int updated,
                             List<String> messages, String syncBatchId) {
    }
}
