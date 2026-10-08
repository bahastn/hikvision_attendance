package com.attendance.controller;

import com.attendance.dto.*;
import com.attendance.service.AttendanceService;
import com.attendance.service.CameraService;
import com.attendance.service.EmployeeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
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
        return ResponseEntity.ok(ApiResponse.success("Employee created", employeeService.createEmployee(dto)));
    }

    @PutMapping("/employees/{id}")
    public ResponseEntity<ApiResponse<EmployeeDTO>> updateEmployee(@PathVariable Long id, @RequestBody EmployeeDTO dto) {
        return ResponseEntity.ok(ApiResponse.success("Employee updated", employeeService.updateEmployee(id, dto)));
    }

    @DeleteMapping("/employees/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteEmployee(@PathVariable Long id) {
        employeeService.deleteEmployee(id);
        return ResponseEntity.ok(ApiResponse.success("Employee deleted", null));
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
}
