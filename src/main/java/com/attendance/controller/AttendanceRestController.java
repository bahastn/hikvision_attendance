package com.attendance.controller;

import com.attendance.dto.*;
import com.attendance.security.AccessControlService;
import com.attendance.security.AppModule;
import com.attendance.security.GroupScope;
import com.attendance.security.PermissionAction;
import com.attendance.security.RequiresPermission;
import com.attendance.service.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.attendance.security.AppModule.*;
import static com.attendance.security.PermissionAction.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
@Slf4j
public class AttendanceRestController {

    private final EmployeeService employeeService;
    private final AttendanceService attendanceService;
    private final CameraService cameraService;
    private final FaceEnrollmentService faceEnrollmentService;
    private final CameraFaceLibraryService cameraFaceLibraryService;
    private final AttendanceSyncService attendanceSyncService;
    private final AccessControlService access;

    // ------------------------------------------------------------------ Employee APIs

    @GetMapping("/employees")
    @RequiresPermission(module = EMPLOYEES, action = VIEW)
    public ResponseEntity<ApiResponse<List<EmployeeDTO>>> getAllEmployees() {
        return ResponseEntity.ok(ApiResponse.success(
                access.filter(employeeService.getAllEmployees(), EMPLOYEES, VIEW, EmployeeDTO::getGroupId)));
    }

    /** Employee pick-list, also used by the Leaves, Attendance and Groups pages. */
    @GetMapping("/employees/active")
    @RequiresPermission(module = EMPLOYEES, action = VIEW)
    @RequiresPermission(module = LEAVES, action = VIEW)
    @RequiresPermission(module = ATTENDANCE, action = VIEW)
    @RequiresPermission(module = GROUPS, action = VIEW)
    public ResponseEntity<ApiResponse<List<EmployeeDTO>>> getActiveEmployees() {
        GroupScope visible = access.current().unionScope(VIEW, EMPLOYEES, LEAVES, ATTENDANCE, GROUPS);
        GroupScope fullDetail = access.scope(EMPLOYEES, VIEW);
        List<EmployeeDTO> list = access.filter(employeeService.getActiveEmployees(), visible, EmployeeDTO::getGroupId)
                .stream()
                .map(dto -> fullDetail.allows(dto.getGroupId()) ? dto : pickListView(dto))
                .toList();
        return ResponseEntity.ok(ApiResponse.success(list));
    }

    /**
     * Minimal view for pick-lists on other modules (Leaves, Attendance, Groups):
     * enough to choose an employee, without the personal details only the Employees
     * module is allowed to see (email, phone, position, face data).
     */
    private EmployeeDTO pickListView(EmployeeDTO full) {
        return EmployeeDTO.builder()
                .id(full.getId())
                .employeeId(full.getEmployeeId())
                .fullName(full.getFullName())
                .fullNameKurdish(full.getFullNameKurdish())
                .isActive(full.getIsActive())
                .groupId(full.getGroupId())
                .groupName(full.getGroupName())
                .build();
    }

    @GetMapping("/employees/{id}")
    @RequiresPermission(module = EMPLOYEES, action = VIEW)
    public ResponseEntity<ApiResponse<EmployeeDTO>> getEmployee(@PathVariable Long id) {
        access.requireEmployee(EMPLOYEES, VIEW, id);
        return ResponseEntity.ok(ApiResponse.success(employeeService.getEmployeeById(id)));
    }

    @PostMapping("/employees")
    @RequiresPermission(module = EMPLOYEES, action = MODIFY)
    public ResponseEntity<ApiResponse<EmployeeDTO>> createEmployee(@RequestBody EmployeeDTO dto) {
        access.requireGroup(EMPLOYEES, MODIFY, dto.getGroupId());
        try {
            return ResponseEntity.ok(ApiResponse.success("Employee created", employeeService.createEmployee(dto)));
        } catch (AccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Creating employee failed: {}", e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Could not add employee: " + e.getMessage()));
        }
    }

    @PutMapping("/employees/{id}")
    @RequiresPermission(module = EMPLOYEES, action = MODIFY)
    public ResponseEntity<ApiResponse<EmployeeDTO>> updateEmployee(@PathVariable Long id, @RequestBody EmployeeDTO dto) {
        access.requireEmployee(EMPLOYEES, MODIFY, id);
        if (dto.getGroupId() != null) {
            // Moving to another group needs MODIFY on the destination group too.
            access.requireGroup(EMPLOYEES, MODIFY, dto.getGroupId());
        }
        try {
            return ResponseEntity.ok(ApiResponse.success("Employee updated", employeeService.updateEmployee(id, dto)));
        } catch (Exception e) {
            log.warn("Updating employee {} failed: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Could not update employee: " + e.getMessage()));
        }
    }

    /** Soft delete (deactivates the employee and removes the face from cameras). */
    @DeleteMapping("/employees/{id}")
    @RequiresPermission(module = EMPLOYEES, action = DELETE)
    public ResponseEntity<ApiResponse<Void>> deleteEmployee(@PathVariable Long id) {
        access.requireEmployee(EMPLOYEES, DELETE, id);
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
    @RequiresPermission(module = EMPLOYEES, action = MODIFY)
    public ResponseEntity<ApiResponse<EmployeeDTO>> enrollFace(@PathVariable Long id,
                                                               @RequestParam("photo") MultipartFile photo,
                                                               @RequestParam(required = false) String cameraIds) {
        access.requireEmployee(EMPLOYEES, MODIFY, id);
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
    @RequiresPermission(module = EMPLOYEES, action = MODIFY)
    public ResponseEntity<ApiResponse<EmployeeDTO>> createEmployeeWithFace(
            @RequestParam String employeeId,
            @RequestParam String fullName,
            @RequestParam(required = false) String fullNameKurdish,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) Long groupId,
            @RequestParam(value = "photo", required = false) MultipartFile photo,
            @RequestParam(required = false) String cameraIds) {
        access.requireGroup(EMPLOYEES, MODIFY, groupId);
        try {
            EmployeeDTO dto = EmployeeDTO.builder()
                    .employeeId(employeeId)
                    .fullName(fullName)
                    .fullNameKurdish(fullNameKurdish)
                    .department(department)
                    .position(position)
                    .category(category)
                    .email(email)
                    .phone(phone)
                    .groupId(groupId)
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
    @RequiresPermission(module = EMPLOYEES, action = MODIFY)
    public ResponseEntity<ApiResponse<EmployeeDTO>> removeFace(@PathVariable Long id) {
        access.requireEmployee(EMPLOYEES, MODIFY, id);
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
    @RequiresPermission(module = EMPLOYEES, action = VIEW)
    public ResponseEntity<ApiResponse<List<CameraTargetDTO>>> getCameraTargets(@PathVariable Long id) {
        access.requireEmployee(EMPLOYEES, VIEW, id);
        return ResponseEntity.ok(ApiResponse.success(faceEnrollmentService.getCameraTargets(id)));
    }

    /** Push the employee's stored face photo to a single camera. */
    @PostMapping("/employees/{id}/camera-targets/{cameraId}")
    @RequiresPermission(module = EMPLOYEES, action = MODIFY)
    public ResponseEntity<ApiResponse<EmployeeDTO>> enrollOnCamera(@PathVariable Long id,
                                                                   @PathVariable Long cameraId) {
        access.requireEmployee(EMPLOYEES, MODIFY, id);
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
    @RequiresPermission(module = EMPLOYEES, action = MODIFY)
    public ResponseEntity<ApiResponse<EmployeeDTO>> removeFromCamera(@PathVariable Long id,
                                                                     @PathVariable Long cameraId) {
        access.requireEmployee(EMPLOYEES, MODIFY, id);
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
    @RequiresPermission(module = EMPLOYEES, action = MODIFY)
    public ResponseEntity<ApiResponse<EmployeeDTO>> activateEmployee(@PathVariable Long id) {
        access.requireEmployee(EMPLOYEES, MODIFY, id);
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
    @RequiresPermission(module = EMPLOYEES, action = MODIFY)
    public ResponseEntity<ApiResponse<EmployeeDTO>> deactivateEmployee(@PathVariable Long id) {
        access.requireEmployee(EMPLOYEES, MODIFY, id);
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
    @RequiresPermission(module = EMPLOYEES, action = DELETE)
    public ResponseEntity<ApiResponse<Void>> permanentlyDeleteEmployee(@PathVariable Long id) {
        access.requireEmployee(EMPLOYEES, DELETE, id);
        try {
            employeeService.permanentlyDeleteEmployee(id);
            return ResponseEntity.ok(ApiResponse.success("Employee permanently deleted", null));
        } catch (Exception e) {
            log.warn("Permanent delete failed for employee {}: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Delete failed: " + e.getMessage()));
        }
    }

    // ------------------------------------------------------------------ Attendance APIs

    @GetMapping("/attendance/today")
    @RequiresPermission(module = ATTENDANCE, action = VIEW)
    public ResponseEntity<ApiResponse<List<AttendanceDTO>>> getTodayAttendance() {
        return ResponseEntity.ok(ApiResponse.success(
                access.filter(attendanceService.getTodayAttendance(), ATTENDANCE, VIEW, AttendanceDTO::getGroupId)));
    }

    @GetMapping("/attendance/date/{date}")
    @RequiresPermission(module = ATTENDANCE, action = VIEW)
    public ResponseEntity<ApiResponse<List<AttendanceDTO>>> getAttendanceByDate(
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ResponseEntity.ok(ApiResponse.success(
                access.filter(attendanceService.getAttendanceByDate(date), ATTENDANCE, VIEW, AttendanceDTO::getGroupId)));
    }

    @GetMapping("/attendance/employee/{employeeId}")
    @RequiresPermission(module = ATTENDANCE, action = VIEW)
    public ResponseEntity<ApiResponse<List<AttendanceDTO>>> getEmployeeAttendance(
            @PathVariable Long employeeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        access.requireEmployee(ATTENDANCE, VIEW, employeeId);
        return ResponseEntity.ok(ApiResponse.success(
                attendanceService.getEmployeeAttendance(employeeId, start, end)));
    }

    /** Record a detection by hand (employeeId = the employee's code, not the database id). */
    @PostMapping("/attendance/detect")
    @RequiresPermission(module = ATTENDANCE, action = MODIFY)
    public ResponseEntity<ApiResponse<AttendanceDTO>> processDetection(
            @RequestParam String employeeId,
            @RequestParam String deviceId,
            @RequestParam(required = false) Double similarity,
            @RequestParam(required = false, defaultValue = "IN") String direction,
            @RequestParam(required = false) String faceImageUrl) {
        access.requireEmployeeCode(ATTENDANCE, MODIFY, employeeId);
        return ResponseEntity.ok(ApiResponse.success(
                attendanceService.processDetection(employeeId, deviceId, similarity, direction, faceImageUrl)));
    }

    @PostMapping("/attendance/manual-checkin/{employeeId}")
    @RequiresPermission(module = ATTENDANCE, action = MODIFY)
    public ResponseEntity<ApiResponse<AttendanceDTO>> manualCheckIn(@PathVariable Long employeeId) {
        access.requireEmployee(ATTENDANCE, MODIFY, employeeId);
        return ResponseEntity.ok(ApiResponse.success(
                attendanceService.manualCheckIn(employeeId)));
    }

    @PostMapping("/attendance/manual-checkout/{recordId}")
    @RequiresPermission(module = ATTENDANCE, action = MODIFY)
    public ResponseEntity<ApiResponse<AttendanceDTO>> manualCheckOut(@PathVariable Long recordId) {
        access.requireAttendanceRecord(MODIFY, recordId);
        return ResponseEntity.ok(ApiResponse.success(
                attendanceService.manualCheckOut(recordId)));
    }

    /** Dashboard counters, limited to the groups the user can see. */
    @GetMapping("/attendance/stats")
    @RequiresPermission(module = ATTENDANCE, action = VIEW)
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStats() {
        GroupScope scope = access.scope(ATTENDANCE, VIEW);
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalEmployees", scope.isAll()
                ? employeeService.getTotalCount()
                : access.filter(employeeService.getActiveEmployees(), scope, EmployeeDTO::getGroupId).size());
        stats.put("todayRecords",
                access.filter(attendanceService.getTodayAttendance(), scope, AttendanceDTO::getGroupId).size());
        return ResponseEntity.ok(ApiResponse.success(stats));
    }

    // ------------------------------------------------------------------ Camera APIs

    @GetMapping("/cameras")
    @RequiresPermission(module = CAMERAS, action = VIEW)
    public ResponseEntity<ApiResponse<List<CameraDTO>>> getAllCameras() {
        return ResponseEntity.ok(ApiResponse.success(cameraService.getAllCameras()));
    }

    /** Camera pick-list, also needed when pushing employee faces to cameras. */
    @GetMapping("/cameras/active")
    @RequiresPermission(module = CAMERAS, action = VIEW)
    @RequiresPermission(module = EMPLOYEES, action = MODIFY)
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    @RequiresPermission(module = ATTENDANCE_SYNC, action = VIEW)
    public ResponseEntity<ApiResponse<List<CameraDTO>>> getActiveCameras() {
        return ResponseEntity.ok(ApiResponse.success(cameraService.getActiveCameras()));
    }

    @PostMapping("/cameras")
    @RequiresPermission(module = CAMERAS, action = MODIFY)
    public ResponseEntity<ApiResponse<CameraDTO>> addCamera(@RequestBody CameraDTO dto) {
        log.info("Adding camera: name='{}' ip='{}' by {}", dto.getCameraName(), dto.getIpAddress(),
                access.currentUsername());
        return ResponseEntity.ok(ApiResponse.success("Camera added", cameraService.addCamera(dto)));
    }

    @PutMapping("/cameras/{id}")
    @RequiresPermission(module = CAMERAS, action = MODIFY)
    public ResponseEntity<ApiResponse<CameraDTO>> updateCamera(@PathVariable Long id, @RequestBody CameraDTO dto) {
        log.info("Updating camera {} (password changed: {}) by {}", id,
                dto.getPassword() != null && !dto.getPassword().isBlank(), access.currentUsername());
        return ResponseEntity.ok(ApiResponse.success("Camera updated", cameraService.updateCamera(id, dto)));
    }

    @DeleteMapping("/cameras/{id}")
    @RequiresPermission(module = CAMERAS, action = DELETE)
    public ResponseEntity<ApiResponse<Void>> deleteCamera(@PathVariable Long id) {
        cameraService.deleteCamera(id);
        return ResponseEntity.ok(ApiResponse.success("Camera deleted", null));
    }

    @PostMapping("/cameras/{id}/test")
    @RequiresPermission(module = CAMERAS, action = MODIFY)
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
    @RequiresPermission(module = CAMERAS, action = VIEW)
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
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
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
    @RequiresPermission(module = FACE_RECOGNITION, action = MODIFY)
    public ResponseEntity<ApiResponse<CameraFaceLibraryService.SyncResult>> syncCameraFaceLibrary(
            @PathVariable Long id) {
        // Imports people from the camera as employees without a group: needs org-wide employee rights.
        access.requireGroup(EMPLOYEES, MODIFY, null);
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
    @RequiresPermission(module = FACE_RECOGNITION, action = MODIFY)
    public ResponseEntity<ApiResponse<Map<String, CameraFaceLibraryService.SyncResult>>> syncAllCameraFaceLibraries() {
        access.requireGroup(EMPLOYEES, MODIFY, null);
        return ResponseEntity.ok(ApiResponse.success("Sync all completed", cameraFaceLibraryService.syncAllCameras()));
    }

    /** Remove one person from the camera's face library by PID. */
    @DeleteMapping("/cameras/{id}/face-library/{pid}")
    @RequiresPermission(module = FACE_RECOGNITION, action = DELETE)
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
    @RequiresPermission(module = CAMERAS, action = MODIFY)
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

    // ------------------------------------------------------------------ Attendance Sync

    /**
     * Sync attendance records from all active cameras for a date range.
     * Downloads all face-comparison matches from the cameras and creates
     * missing attendance records in the database.
     */
    @PostMapping("/attendance/sync")
    @RequiresPermission(module = ATTENDANCE_SYNC, action = MODIFY)
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
    @RequiresPermission(module = ATTENDANCE_SYNC, action = MODIFY)
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
    @RequiresPermission(module = ATTENDANCE_SYNC, action = VIEW)
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
    @RequiresPermission(module = ATTENDANCE_SYNC, action = MODIFY)
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
