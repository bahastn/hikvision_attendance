package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.LeaveTypeDTO;
import com.attendance.security.RequiresPermission;
import com.attendance.service.LeaveTypeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.attendance.security.AppModule.LEAVES;
import static com.attendance.security.PermissionAction.*;

@RestController
@RequestMapping("/api/leave-types")
@RequiredArgsConstructor
@Slf4j
public class LeaveTypeRestController {

    private final LeaveTypeService leaveTypeService;

    @GetMapping
    @RequiresPermission(module = LEAVES, action = VIEW)
    public ResponseEntity<ApiResponse<List<LeaveTypeDTO>>> getAll() {
        return ResponseEntity.ok(ApiResponse.success(leaveTypeService.getAllLeaveTypes()));
    }

    @GetMapping("/active")
    @RequiresPermission(module = LEAVES, action = VIEW)
    public ResponseEntity<ApiResponse<List<LeaveTypeDTO>>> getActive() {
        return ResponseEntity.ok(ApiResponse.success(leaveTypeService.getActiveLeaveTypes()));
    }

    @PostMapping
    @RequiresPermission(module = LEAVES, action = MODIFY)
    public ResponseEntity<ApiResponse<LeaveTypeDTO>> create(@RequestBody LeaveTypeDTO dto) {
        log.info("Creating leave type: en='{}' ku='{}'", dto.getNameEn(), dto.getNameKu());
        try {
            return ResponseEntity.ok(ApiResponse.success("Leave type created", leaveTypeService.createLeaveType(dto)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @PutMapping("/{id}")
    @RequiresPermission(module = LEAVES, action = MODIFY)
    public ResponseEntity<ApiResponse<LeaveTypeDTO>> update(@PathVariable Long id, @RequestBody LeaveTypeDTO dto) {
        log.info("Updating leave type {}: en='{}' ku='{}'", id, dto.getNameEn(), dto.getNameKu());
        try {
            return ResponseEntity.ok(ApiResponse.success("Leave type updated", leaveTypeService.updateLeaveType(id, dto)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(module = LEAVES, action = DELETE)
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable Long id) {
        leaveTypeService.deactivateLeaveType(id);
        return ResponseEntity.ok(ApiResponse.success("Leave type deactivated", null));
    }

    @DeleteMapping("/{id}/permanent")
    @RequiresPermission(module = LEAVES, action = DELETE)
    public ResponseEntity<ApiResponse<Void>> permanentlyDelete(@PathVariable Long id) {
        try {
            leaveTypeService.permanentlyDeleteLeaveType(id);
            return ResponseEntity.ok(ApiResponse.success("Leave type deleted", null));
        } catch (RuntimeException e) {
            log.warn("Permanent delete failed for leave type {}: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }
}
