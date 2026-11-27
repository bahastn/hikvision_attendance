package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.EmployeeDTO;
import com.attendance.dto.FaceCaptureDTO;
import com.attendance.security.AccessControlService;
import com.attendance.security.RequiresPermission;
import com.attendance.service.FaceCaptureService;
import com.attendance.service.FaceEnrollmentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

import static com.attendance.security.AppModule.EMPLOYEES;
import static com.attendance.security.AppModule.FACE_RECOGNITION;
import static com.attendance.security.PermissionAction.*;

/**
 * The rolling 24-hour face capture library: list what the cameras captured,
 * delete single entries, and turn a capture into an employee pushed to the
 * selected cameras' face libraries.
 */
@RestController
@RequestMapping("/api/face-captures")
@RequiredArgsConstructor
@Slf4j
public class FaceCaptureRestController {

    private final FaceCaptureService faceCaptureService;
    private final AccessControlService access;

    /** Captures still within the retention window (24h), newest first. Optional camera filter. */
    @GetMapping
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public ResponseEntity<ApiResponse<List<FaceCaptureDTO>>> listCaptures(
            @RequestParam(required = false) Long cameraId) {
        return ResponseEntity.ok(ApiResponse.success(faceCaptureService.listActiveCaptures(cameraId)));
    }

    /** Delete one capture (row + image file) immediately. */
    @DeleteMapping("/{id}")
    @RequiresPermission(module = FACE_RECOGNITION, action = DELETE)
    public ResponseEntity<ApiResponse<Void>> deleteCapture(@PathVariable Long id) {
        faceCaptureService.deleteCapture(id);
        return ResponseEntity.ok(ApiResponse.success("Capture deleted", null));
    }

    /**
     * Uncertain InsightFace suggestions awaiting a human decision (confidence between the low and
     * high thresholds). Newest first.
     */
    @GetMapping("/pending-review")
    @RequiresPermission(module = FACE_RECOGNITION, action = VIEW)
    public ResponseEntity<ApiResponse<List<FaceCaptureDTO>>> pendingReview() {
        return ResponseEntity.ok(ApiResponse.success(faceCaptureService.listPendingReview()));
    }

    /**
     * Confirm a pending review: records attendance for the chosen (or suggested) employee and marks
     * the capture CONFIRMED. {@code employeeId} is optional — the stored candidate is used when absent.
     */
    @PostMapping("/{id}/confirm")
    @RequiresPermission(module = FACE_RECOGNITION, action = MODIFY)
    public ResponseEntity<ApiResponse<Void>> confirmReview(
            @PathVariable Long id, @RequestParam(required = false) String employeeId) {
        try {
            faceCaptureService.confirmPendingReview(id, employeeId);
            return ResponseEntity.ok(ApiResponse.success("Attendance recorded", null));
        } catch (Exception e) {
            log.warn("Confirming review {} failed: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Could not confirm: " + e.getMessage()));
        }
    }

    /** Reject a pending review: no attendance is recorded; the capture is marked REJECTED. */
    @PostMapping("/{id}/reject")
    @RequiresPermission(module = FACE_RECOGNITION, action = MODIFY)
    public ResponseEntity<ApiResponse<Void>> rejectReview(@PathVariable Long id) {
        try {
            faceCaptureService.rejectPendingReview(id);
            return ResponseEntity.ok(ApiResponse.success("Rejected", null));
        } catch (Exception e) {
            log.warn("Rejecting review {} failed: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Could not reject: " + e.getMessage()));
        }
    }

    /**
     * Turn a captured face into an employee: creates the employee, copies the capture
     * as the employee's photo and pushes it to the face library of the selected cameras.
     * Multipart fields mirror the employee form; cameraIds is a comma-separated list
     * (all active cameras when absent/empty).
     */
    @PostMapping("/{id}/enroll")
    @RequiresPermission(module = FACE_RECOGNITION, action = MODIFY)
    public ResponseEntity<ApiResponse<EmployeeDTO>> enrollCapture(
            @PathVariable Long id,
            @RequestParam String employeeId,
            @RequestParam String fullName,
            @RequestParam(required = false) String fullNameKurdish,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String position,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) Long groupId,
            @RequestParam(required = false) String cameraIds) {
        // Creates an employee: needs employee MODIFY on the target group (no group = all groups).
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
