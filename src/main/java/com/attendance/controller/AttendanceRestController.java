package com.attendance.controller;

import com.attendance.dto.*;
import com.attendance.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class AttendanceRestController {

    private final EmployeeService employeeService;
    private final AttendanceService attendanceService;
    private final CameraService cameraService;
    private final FaceEnrollmentService faceEnrollmentService;
    private final CameraFaceLibraryService cameraFaceLibraryService;
    private final AttendanceSyncService attendanceSyncService;

    // Employee APIs
    @GetMapping("/employees")
    public ResponseEntity<ApiResponse<List<EmployeeDTO>>> getAllEmployees() {
        return ResponseEntity.ok(ApiResponse.success(employeeService.getAllEmployees()));
    }

    @GetMapping("/employees/active")
    public ResponseEntity<ApiResponse<List<EmployeeDTO>>> getActiveEmployees() {
        return ResponseEntity.ok(ApiResponse.success(employeeService.getActiveEmployees()));
    }

    @GetMapping("/employees/{id}")
    public ResponseEntity<ApiResponse<EmployeeDTO>> getEmployee(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(employeeService.getEmployeeById(id)));
    }

    @PostMapping("/employees")
    public ResponseEntity<ApiResponse<EmployeeDTO>> createEmployee(@RequestBody EmployeeDTO dto) {
        try {
            return ResponseEntity.ok(ApiResponse.success("Employee created", employeeService.createEmployee(dto)));
        } catch (Exception e) {
            log.warn("Creating employee failed: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Could not add employee: " + e.getMessage()));
        }
    }

    @PutMapping("/employees/{id}")
    public ResponseEntity<ApiResponse<EmployeeDTO>> updateEmployee(@PathVariable Long id, @RequestBody EmployeeDTO dto) {
        try {
            return ResponseEntity.ok(ApiResponse.success("Employee updated", employeeService.updateEmployee(id, dto)));
        } catch (Exception e) {
            log.warn("Updating employee {} failed: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Could not update employee: " + e.getMessage()));
        }
    }

    @DeleteMapping("/employees/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteEmployee(@PathVariable Long id) {
        try {
            faceEnrollmentService.removeFace(id);
        } catch (Exception e) {
            log.warn("Could not remove face of employee {} from camera: {}", id, e.getMessage());
        }
        employeeService.deleteEmployee(id);
        return ResponseEntity.ok(ApiResponse.success("Employee deleted", null));
    }

    /**
     * Enroll or replace an employee's face: stores the photo and pushes it to the
     * face library of the selected cameras (all active cameras when cameraIds is
     * not given; the camera assigns the PID used to map match events back to this
     * employee).
     */
    @PostMapping(value = "/employees/{id}/face", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<EmployeeDTO>> enrollFace(@PathVariable Long id,
                                                               @RequestParam("photo") MultipartFile photo,
                                                               @RequestParam(required = false) String cameraIds) {
        try {
            if (photo == null || photo.isEmpty()) {
                return ResponseEntity.badRequest().body(ApiResponse.error("Please choose a photo"));
            }
            FaceEnrollmentService.EnrollmentResult result =
                    faceEnrollmentService.enrollFace(id, photo.getBytes(), parseCameraIds(cameraIds));
            return ResponseEntity.ok(ApiResponse.success(result.message(), result.employee()));
        } catch (Exception e) {
            log.warn("Face enrollment failed for employee {}: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Face enrollment failed: " + e.getMessage()));
        }
    }

    /**
     * Create an employee together with a face photo in one request: saves the
     * employee, then pushes the photo to the face library of the selected cameras
     * (all active cameras when cameraIds is not given).
     */
    @PostMapping(value = "/employees/with-face", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<EmployeeDTO>> createEmployeeWithFace(
            @RequestParam String employeeId,
            @RequestParam String fullName,
            @RequestParam(required = false) String fullNameKurdish,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phone,
            @RequestParam(value = "photo", required = false) MultipartFile photo,
            @RequestParam(required = false) String cameraIds) {
        try {
            EmployeeDTO dto = EmployeeDTO.builder()
                    .employeeId(employeeId)
                    .fullName(fullName)
                    .fullNameKurdish(fullNameKurdish)
                    .department(department)
                    .position(position)
                    .email(email)
                    .phone(phone)
                    .build();
            EmployeeDTO saved = employeeService.createEmployee(dto);
            if (photo == null || photo.isEmpty()) {
                return ResponseEntity.ok(ApiResponse.success("Employee created (no photo)", saved));
            }
            FaceEnrollmentService.EnrollmentResult result =
                    faceEnrollmentService.enrollFace(saved.getId(), photo.getBytes(), parseCameraIds(cameraIds));
            return ResponseEntity.ok(ApiResponse.success(result.message(), result.employee()));
        } catch (Exception e) {
            log.warn("Creating employee with face failed: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Could not add employee: " + e.getMessage()));
        }
    }

    private List<Long> parseCameraIds(String cameraIds) {
        if (cameraIds == null || cameraIds.isBlank()) {
            return List.of();
        }
        return Arrays.stream(cameraIds.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Long::valueOf)
                .toList();
    }

    /** Remove the employee's face from every camera's face library. */
    @DeleteMapping("/employees/{id}/face")
    public ResponseEntity<ApiResponse<EmployeeDTO>> removeFace(@PathVariable Long id) {
        try {
            faceEnrollmentService.removeFace(id);
            return ResponseEntity.ok(ApiResponse.success("Face removed from camera",
                    employeeService.getEmployeeById(id)));
        } catch (Exception e) {
            log.warn("Face removal failed for employee {}: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Face removal failed: " + e.getMessage()));
        }
    }

    /** All active cameras with this employee's enrollment state on each (camera-target UI). */
    @GetMapping("/employees/{id}/camera-targets")
    public ResponseEntity<ApiResponse<List<CameraTargetDTO>>> getCameraTargets(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(faceEnrollmentService.getCameraTargets(id)));
    }

    /** Push the employee's stored face photo to a single camera. */
    @PostMapping("/employees/{id}/camera-targets/{cameraId}")
    public ResponseEntity<ApiResponse<EmployeeDTO>> enrollOnCamera(@PathVariable Long id,
                                                                   @PathVariable Long cameraId) {
        try {
            FaceEnrollmentService.EnrollmentResult result =
                    faceEnrollmentService.enrollOnCamera(id, cameraId);
            return ResponseEntity.ok(ApiResponse.success(result.message(), result.employee()));
        } catch (Exception e) {
            log.warn("Sending face of employee {} to camera {} failed: {}", id, cameraId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Send failed: " + e.getMessage()));
        }
    }

    /** Remove the employee's face from a single camera. */
    @DeleteMapping("/employees/{id}/camera-targets/{cameraId}")
    public ResponseEntity<ApiResponse<EmployeeDTO>> removeFromCamera(@PathVariable Long id,
                                                                     @PathVariable Long cameraId) {
        try {
            faceEnrollmentService.removeFromCamera(id, cameraId);
            return ResponseEntity.ok(ApiResponse.success("Face removed from camera",
                    employeeService.getEmployeeById(id)));
        } catch (Exception e) {
            log.warn("Removing face of employee {} from camera {} failed: {}", id, cameraId, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Remove failed: " + e.getMessage()));
        }
    }

    /** Activate an employee and push their face back to all active cameras. */
    @PostMapping("/employees/{id}/activate")
    public ResponseEntity<ApiResponse<EmployeeDTO>> activateEmployee(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success("Employee activated and face sent to cameras",
                    employeeService.activateEmployee(id)));
        } catch (Exception e) {
            log.warn("Activation failed for employee {}: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Activation failed: " + e.getMessage()));
        }
    }

    /** Deactivate an employee and remove their face from all cameras. */
    @PostMapping("/employees/{id}/deactivate")
    public ResponseEntity<ApiResponse<EmployeeDTO>> deactivateEmployee(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success("Employee deactivated and face removed from cameras",
                    employeeService.deactivateEmployee(id)));
        } catch (Exception e) {
            log.warn("Deactivation failed for employee {}: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Deactivation failed: " + e.getMessage()));
        }
    }

    /** Permanently delete an employee: removes face from cameras, mappings and the DB record. */
    @DeleteMapping("/employees/{id}/permanent")
    public ResponseEntity<ApiResponse<Void>> permanentlyDeleteEmployee(@PathVariable Long id) {
        try {
            employeeService.permanentlyDeleteEmployee(id);
            return ResponseEntity.ok(ApiResponse.success("Employee permanently deleted", null));
        } catch (Exception e) {
            log.warn("Permanent delete failed for employee {}: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Delete failed: " + e.getMessage()));
        }
    }

    // Attendance APIs
    @GetMapping("/attendance/today")
    public ResponseEntity<ApiResponse<List<AttendanceDTO>>> getTodayAttendance() {
        return ResponseEntity.ok(ApiResponse.success(attendanceService.getTodayAttendance()));
    }

    @GetMapping("/attendance/date/{date}")
    public ResponseEntity<ApiResponse<List<AttendanceDTO>>> getAttendanceByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(attendanceService.getAttendanceByDate(date)));
    }

    @GetMapping("/attendance/employee/{employeeId}")
    public ResponseEntity<ApiResponse<List<AttendanceDTO>>> getEmployeeAttendance(
            @PathVariable Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return ResponseEntity.ok(ApiResponse.success(
                attendanceService.getEmployeeAttendance(employeeId, start, end)));
    }

    @PostMapping("/attendance/detect")
    public ResponseEntity<ApiResponse<AttendanceDTO>> processDetection(
            @RequestParam String employeeId,
            @RequestParam String deviceId,
            @RequestParam(required = false) Double similarity,
            @RequestParam(required = false, defaultValue = "IN") String direction,
            @RequestParam(required = false) String faceImageUrl) {
        return ResponseEntity.ok(ApiResponse.success(
                attendanceService.processDetection(employeeId, deviceId, similarity, direction, faceImageUrl)));
    }

    @PostMapping("/attendance/manual-checkin/{employeeId}")
    public ResponseEntity<ApiResponse<AttendanceDTO>> manualCheckIn(@PathVariable Long employeeId) {
        return ResponseEntity.ok(ApiResponse.success(
                attendanceService.manualCheckIn(employeeId)));
    }

    @PostMapping("/attendance/manual-checkout/{recordId}")
    public ResponseEntity<ApiResponse<AttendanceDTO>> manualCheckOut(@PathVariable Long recordId) {
        return ResponseEntity.ok(ApiResponse.success(
                attendanceService.manualCheckOut(recordId)));
    }

    @GetMapping("/attendance/stats")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalEmployees", employeeService.getTotalCount());
        stats.put("todayRecords", attendanceService.getTodayAttendance().size());
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // Camera APIs
    @GetMapping("/cameras")
    public ResponseEntity<ApiResponse<List<CameraDTO>>> getAllCameras() {
        return ResponseEntity.ok(ApiResponse.success(cameraService.getAllCameras()));
    }

    @GetMapping("/cameras/active")
    public ResponseEntity<ApiResponse<List<CameraDTO>>> getActiveCameras() {
        return ResponseEntity.ok(ApiResponse.success(cameraService.getActiveCameras()));
    }

    @PostMapping("/cameras")
    public ResponseEntity<ApiResponse<CameraDTO>> addCamera(@RequestBody CameraDTO dto) {
        log.info("Adding camera: name='{}' ip='{}' username='{}' passwordHash={} passwordLength={}",
                dto.getCameraName(), dto.getIpAddress(), dto.getUsername(),
                dto.getPassword() != null ? Integer.toHexString(dto.getPassword().hashCode()) : "null",
                dto.getPassword() != null ? dto.getPassword().length() : 0);
        return ResponseEntity.ok(ApiResponse.success("Camera added", cameraService.addCamera(dto)));
    }

    @PutMapping("/cameras/{id}")
    public ResponseEntity<ApiResponse<CameraDTO>> updateCamera(@PathVariable Long id, @RequestBody CameraDTO dto) {
        log.info("Updating camera {}: username='{}' passwordHash={} passwordLength={}",
                id, dto.getUsername(),
                dto.getPassword() != null ? Integer.toHexString(dto.getPassword().hashCode()) : "null",
                dto.getPassword() != null ? dto.getPassword().length() : 0);
        return ResponseEntity.ok(ApiResponse.success("Camera updated", cameraService.updateCamera(id, dto)));
    }

    @DeleteMapping("/cameras/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCamera(@PathVariable Long id) {
        cameraService.deleteCamera(id);
        return ResponseEntity.ok(ApiResponse.success("Camera deleted", null));
    }

    @PostMapping("/cameras/{id}/test")
    public ResponseEntity<ApiResponse<Void>> testCamera(@PathVariable Long id) {
        boolean connected = cameraService.testConnection(id);
        if (connected) {
            return ResponseEntity.ok(ApiResponse.success("Camera connected successfully", null));
        } else {
            return ResponseEntity.ok(ApiResponse.error("Connection failed - check IP, port, username and password"));
        }
    }

    /** Latest JPEG frame from the camera, for the live-view page (browsers can't play RTSP). */
    @GetMapping(value = "/cameras/{id}/snapshot", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> getCameraSnapshot(@PathVariable Long id) {
        return cameraService.getSnapshot(id, 101)
                .map(jpeg -> ResponseEntity.ok()
                        .cacheControl(CacheControl.noStore())
                        .body(jpeg))
                .orElse(ResponseEntity.notFound().build());
    }

    // ---- Camera face library (FDLib) sync ----

    /** List every person stored in the camera's face library, with DB match info. */
    @GetMapping("/cameras/{id}/face-library")
    public ResponseEntity<ApiResponse<List<CameraFaceLibraryService.CameraFacePerson>>> getCameraFaceLibrary(
            @PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success(cameraFaceLibraryService.getCameraFaceLibrary(id)));
        } catch (Exception e) {
            log.warn("Reading face library of camera {} failed: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Could not read camera face library: " + e.getMessage()));
        }
    }

    /** Sync one camera's face library with the database (import new, link existing, remove deactivated). */
    @PostMapping("/cameras/{id}/face-library/sync")
    public ResponseEntity<ApiResponse<CameraFaceLibraryService.SyncResult>> syncCameraFaceLibrary(
            @PathVariable Long id) {
        try {
            return ResponseEntity.ok(ApiResponse.success("Sync completed",
                    cameraFaceLibraryService.syncCameraFaceLibrary(id)));
        } catch (Exception e) {
            log.warn("Face library sync for camera {} failed: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Sync failed: " + e.getMessage()));
        }
    }

    /** Sync every active camera's face library with the database. */
    @PostMapping("/cameras/face-library/sync-all")
    public ResponseEntity<ApiResponse<Map<String, CameraFaceLibraryService.SyncResult>>> syncAllCameraFaceLibraries() {
        return ResponseEntity.ok(ApiResponse.success("Sync all completed", cameraFaceLibraryService.syncAllCameras()));
    }

    /** Remove one person from the camera's face library by PID. */
    @DeleteMapping("/cameras/{id}/face-library/{pid}")
    public ResponseEntity<ApiResponse<Void>> deletePersonFromCamera(@PathVariable Long id,
                                                                    @PathVariable String pid) {
        try {
            boolean ok = cameraFaceLibraryService.deletePersonFromCamera(id, pid);
            if (ok) {
                return ResponseEntity.ok(ApiResponse.success("Person removed from camera face library", null));
            }
            return ResponseEntity.ok(ApiResponse.error("Could not remove person from camera"));
        } catch (Exception e) {
            log.warn("Deleting PID {} from camera {} failed: {}", pid, id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Delete failed: " + e.getMessage()));
        }
    }

    /**
     * Diagnostic endpoint to test camera connection with explicit credentials.
     * Useful for verifying credentials before saving them.
     */
    @PostMapping("/cameras/diagnostic-test")
    public ResponseEntity<ApiResponse<Map<String, Object>>> diagnosticTest(
            @RequestBody Map<String, String> request) {
        String ip = request.get("ipAddress");
        String portStr = request.get("port");
        String protocol = request.getOrDefault("protocol", "http");
        String username = request.get("username");
        String password = request.get("password");

        Integer port = null;
        if (portStr != null && !portStr.isBlank()) {
            try {
                port = Integer.parseInt(portStr.trim());
            } catch (NumberFormatException e) {
                return ResponseEntity.ok(ApiResponse.error("Invalid port number: " + portStr));
            }
        }

        boolean connected = cameraService.testConnectionWithCredentials(ip, port, protocol, username, password);
        
        Map<String, Object> result = new HashMap<>();
        result.put("connected", connected);
        result.put("ipAddress", ip);
        result.put("username", username);
        result.put("passwordProvided", password != null && !password.isBlank());
        
        if (connected) {
            return ResponseEntity.ok(ApiResponse.success("Connection successful!", result));
        } else {
            return ResponseEntity.ok(ApiResponse.<Map<String, Object>>error("Connection failed. Check the console logs for details."));
        }
    }

    // ---- Attendance Sync ----

    /**
     * Sync attendance records from all active cameras for a date range.
     * Downloads all face-comparison matches from the cameras and creates
     * missing attendance records in the database.
     */
    @PostMapping("/attendance/sync")
    public ResponseEntity<ApiResponse<AttendanceSyncService.SyncResult>> syncAttendance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            AttendanceSyncService.SyncResult result = attendanceSyncService.syncAttendanceFromCameras(startDate, endDate);
            return ResponseEntity.ok(ApiResponse.success("Sync completed", result));
        } catch (Exception e) {
            log.error("Attendance sync failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Sync failed: " + e.getMessage()));
        }
    }

    /**
     * Sync attendance records from a specific camera for a date range.
     */
    @PostMapping("/attendance/sync/camera/{cameraId}")
    public ResponseEntity<ApiResponse<AttendanceSyncService.SyncResult>> syncAttendanceFromCamera(
            @PathVariable Long cameraId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            AttendanceSyncService.SyncResult result = attendanceSyncService.syncAttendanceFromCamera(cameraId, startDate, endDate);
            return ResponseEntity.ok(ApiResponse.success("Sync completed", result));
        } catch (Exception e) {
            log.error("Attendance sync failed for camera {}: {}", cameraId, e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Sync failed: " + e.getMessage()));
        }
    }

    /**
     * Preview attendance records from a camera without importing them.
     */
    @GetMapping("/attendance/sync/preview/{cameraId}")
    public ResponseEntity<ApiResponse<List<AttendanceSyncService.CameraAttendanceRecord>>> previewCameraAttendance(
            @PathVariable Long cameraId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        try {
            List<AttendanceSyncService.CameraAttendanceRecord> records = 
                    attendanceSyncService.getCameraAttendance(cameraId, startDate, endDate);
            return ResponseEntity.ok(ApiResponse.success(records));
        } catch (Exception e) {
            log.error("Failed to preview camera attendance: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Preview failed: " + e.getMessage()));
        }
    }

    /**
     * Quick sync for today only - useful for manual trigger.
     */
    @PostMapping("/attendance/sync/today")
    public ResponseEntity<ApiResponse<AttendanceSyncService.SyncResult>> syncTodayAttendance() {
        try {
            LocalDate today = LocalDate.now();
            AttendanceSyncService.SyncResult result = attendanceSyncService.syncAttendanceFromCameras(today, today);
            return ResponseEntity.ok(ApiResponse.success("Today's attendance synced", result));
        } catch (Exception e) {
            log.error("Today's attendance sync failed: {}", e.getMessage(), e);
            return ResponseEntity.ok(ApiResponse.error("Sync failed: " + e.getMessage()));
        }
    }
}
