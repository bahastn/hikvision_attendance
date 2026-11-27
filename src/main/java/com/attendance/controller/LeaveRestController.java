package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.LeaveDTO;
import com.attendance.security.AccessControlService;
import com.attendance.security.RequiresPermission;
import com.attendance.service.LeaveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDate;
import java.util.List;

import static com.attendance.security.AppModule.LEAVES;
import static com.attendance.security.PermissionAction.*;

@RestController
@RequestMapping("/api/leaves")
@RequiredArgsConstructor
@Slf4j
public class LeaveRestController {

    private final LeaveService leaveService;
    private final AccessControlService access;

    @GetMapping
    @RequiresPermission(module = LEAVES, action = VIEW)
    public ResponseEntity<ApiResponse<List<LeaveDTO>>> getAllLeaves() {
        return ok(leaveService.getAllLeaves());
    }

    @GetMapping("/status/{status}")
    @RequiresPermission(module = LEAVES, action = VIEW)
    public ResponseEntity<ApiResponse<List<LeaveDTO>>> getLeavesByStatus(@PathVariable String status) {
        return ok(leaveService.getLeavesByStatus(status));
    }

    @GetMapping("/type/{typeId}")
    @RequiresPermission(module = LEAVES, action = VIEW)
    public ResponseEntity<ApiResponse<List<LeaveDTO>>> getLeavesByType(@PathVariable Long typeId) {
        return ok(leaveService.getLeavesByType(typeId));
    }

    @GetMapping("/active-today")
    @RequiresPermission(module = LEAVES, action = VIEW)
    public ResponseEntity<ApiResponse<List<LeaveDTO>>> getActiveLeavesToday() {
        return ok(leaveService.getActiveLeavesToday());
    }

    @GetMapping("/upcoming-active")
    @RequiresPermission(module = LEAVES, action = VIEW)
    public ResponseEntity<ApiResponse<List<LeaveDTO>>> getUpcomingAndActiveLeaves() {
        return ok(leaveService.getUpcomingAndActiveLeaves());
    }

    @GetMapping("/pending")
    @RequiresPermission(module = LEAVES, action = VIEW)
    public ResponseEntity<ApiResponse<List<LeaveDTO>>> getPendingLeaves() {
        return ok(leaveService.getPendingLeaves());
    }

    @GetMapping("/employee/{employeeId}")
    @RequiresPermission(module = LEAVES, action = VIEW)
    public ResponseEntity<ApiResponse<List<LeaveDTO>>> getLeavesByEmployee(@PathVariable Long employeeId) {
        access.requireEmployee(LEAVES, VIEW, employeeId);
        return ResponseEntity.ok(ApiResponse.success(leaveService.getLeavesByEmployee(employeeId)));
    }

    @GetMapping("/{id}")
    @RequiresPermission(module = LEAVES, action = VIEW)
    public ResponseEntity<ApiResponse<LeaveDTO>> getLeave(@PathVariable Long id) {
        access.requireLeave(VIEW, id);
        return ResponseEntity.ok(ApiResponse.success(leaveService.getLeave(id)));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(module = LEAVES, action = MODIFY)
    public ResponseEntity<ApiResponse<LeaveDTO>> createLeave(
            @RequestParam Long employeeDbId,
            @RequestParam(required = false) Long leaveTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String reason,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        access.requireEmployee(LEAVES, MODIFY, employeeDbId);
        LeaveDTO dto = LeaveDTO.builder()
                .employeeDbId(employeeDbId)
                .leaveTypeId(leaveTypeId)
                .startDate(startDate)
                .endDate(endDate)
                .reason(reason)
                .build();
        log.info("Creating leave for employee: {} type: {} from: {} to: {} attachment: {} by {}",
                employeeDbId, leaveTypeId, startDate, endDate, file != null && !file.isEmpty(), access.currentUsername());
        return ResponseEntity.ok(ApiResponse.success("Leave request created", leaveService.createLeave(dto, file)));
    }

    @PostMapping(value = "/{id}/update", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermission(module = LEAVES, action = MODIFY)
    public ResponseEntity<ApiResponse<LeaveDTO>> updateLeave(
            @PathVariable Long id,
            @RequestParam(required = false) Long leaveTypeId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(required = false) String reason,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "removeAttachment", required = false, defaultValue = "false") boolean removeAttachment) {
        access.requireLeave(MODIFY, id);
        LeaveDTO dto = LeaveDTO.builder()
                .leaveTypeId(leaveTypeId)
                .startDate(startDate)
                .endDate(endDate)
                .reason(reason)
                .build();
        log.info("Updating leave {} from: {} to: {} by {}", id, startDate, endDate, access.currentUsername());
        return ResponseEntity.ok(ApiResponse.success("Leave updated",
                leaveService.updateLeave(id, dto, file, removeAttachment)));
    }

    @GetMapping("/{id}/attachment")
    @RequiresPermission(module = LEAVES, action = VIEW)
    public ResponseEntity<byte[]> getAttachment(@PathVariable Long id) {
        access.requireLeave(VIEW, id);
        LeaveService.AttachmentResource res = leaveService.loadAttachment(id);
        if (res == null) {
            return ResponseEntity.notFound().build();
        }
        ContentDisposition disposition = ContentDisposition.inline()
                .filename(res.filename() != null ? res.filename() : "attachment")
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(res.contentType()))
                .header("Content-Disposition", disposition.toString())
                .body(res.data());
    }

    @PostMapping("/{id}/approve")
    @RequiresPermission(module = LEAVES, action = MODIFY)
    public ResponseEntity<ApiResponse<LeaveDTO>> approveLeave(@PathVariable Long id) {
        access.requireLeave(MODIFY, id);
        return ResponseEntity.ok(ApiResponse.success("Leave approved",
                leaveService.approveLeave(id, access.currentUsername())));
    }

    @PostMapping("/{id}/reject")
    @RequiresPermission(module = LEAVES, action = MODIFY)
    public ResponseEntity<ApiResponse<LeaveDTO>> rejectLeave(@PathVariable Long id) {
        access.requireLeave(MODIFY, id);
        return ResponseEntity.ok(ApiResponse.success("Leave rejected",
                leaveService.rejectLeave(id, access.currentUsername())));
    }

    @PostMapping("/{id}/cancel")
    @RequiresPermission(module = LEAVES, action = MODIFY)
    public ResponseEntity<ApiResponse<LeaveDTO>> cancelLeave(@PathVariable Long id) {
        access.requireLeave(MODIFY, id);
        return ResponseEntity.ok(ApiResponse.success("Leave cancelled", leaveService.cancelLeave(id)));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(module = LEAVES, action = DELETE)
    public ResponseEntity<ApiResponse<Void>> deleteLeave(@PathVariable Long id) {
        access.requireLeave(DELETE, id);
        leaveService.deleteLeave(id);
        return ResponseEntity.ok(ApiResponse.success("Leave deleted", null));
    }

    private ResponseEntity<ApiResponse<List<LeaveDTO>>> ok(List<LeaveDTO> leaves) {
        return ResponseEntity.ok(ApiResponse.success(access.filter(leaves, LEAVES, VIEW, LeaveDTO::getGroupId)));
    }
}
