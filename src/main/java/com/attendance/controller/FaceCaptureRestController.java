package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.EmployeeDTO;
import com.attendance.dto.FaceCaptureDTO;
import com.attendance.service.FaceCaptureService;
import com.attendance.service.FaceEnrollmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * The rolling 24-hour face capture library: list what the cameras captured,
 * delete single entries, and turn a capture into an employee pushed to the
 * selected cameras' face libraries.
 */
@RestController
@RequestMapping("/api/face-captures")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class FaceCaptureRestController {

    private final FaceCaptureService faceCaptureService;

    /** Captures still within the retention window (24h), newest first. Optional camera filter. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<FaceCaptureDTO>>> listCaptures(
            @RequestParam(required = false) Long cameraId) {
        return ResponseEntity.ok(ApiResponse.success(faceCaptureService.listActiveCaptures(cameraId)));
    }

    /** Delete one capture (row + image file) immediately. */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteCapture(@PathVariable Long id) {
        faceCaptureService.deleteCapture(id);
        return ResponseEntity.ok(ApiResponse.success("Capture deleted", null));
    }

    /**
     * Turn a captured face into an employee: creates the employee, copies the capture
     * as the employee's photo and pushes it to the face library of the selected cameras.
     * Multipart fields mirror the employee form; cameraIds is a comma-separated list
     * (all active cameras when absent/empty).
     */
    @PostMapping("/{id}/enroll")
    public ResponseEntity<ApiResponse<EmployeeDTO>> enrollCapture(
            @PathVariable Long id,
            @RequestParam String employeeId,
            @RequestParam String fullName,
            @RequestParam(required = false) String fullNameKurdish,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phone,
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
            FaceEnrollmentService.EnrollmentResult result =
                    faceCaptureService.enrollCaptureAsEmployee(id, dto, parseCameraIds(cameraIds));
            return ResponseEntity.ok(ApiResponse.success(result.message(), result.employee()));
        } catch (Exception e) {
            log.warn("Enrolling capture {} as employee failed: {}", id, e.getMessage());
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
}
