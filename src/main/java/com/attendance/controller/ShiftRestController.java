package com.attendance.controller;

import com.attendance.security.RequiresPermission;
import static com.attendance.security.AppModule.GROUPS;
import static com.attendance.security.AppModule.SHIFTS;
import static com.attendance.security.AppModule.SHIFT_SCHEDULE;
import static com.attendance.security.PermissionAction.*;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.ShiftDTO;
import com.attendance.service.ShiftService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/shifts")
@RequiredArgsConstructor
@Slf4j
public class ShiftRestController {

    private final ShiftService shiftService;

    @GetMapping
    @RequiresPermission(module = SHIFTS, action = VIEW)
    @RequiresPermission(module = GROUPS, action = VIEW)
    @RequiresPermission(module = SHIFT_SCHEDULE, action = VIEW)
    public ResponseEntity<ApiResponse<List<ShiftDTO>>> getAllShifts() {
        return ResponseEntity.ok(ApiResponse.success(shiftService.getAllShifts()));
    }

    @GetMapping("/active")
    @RequiresPermission(module = SHIFTS, action = VIEW)
    @RequiresPermission(module = GROUPS, action = VIEW)
    @RequiresPermission(module = SHIFT_SCHEDULE, action = VIEW)
    public ResponseEntity<ApiResponse<List<ShiftDTO>>> getActiveShifts() {
        return ResponseEntity.ok(ApiResponse.success(shiftService.getActiveShifts()));
    }

    @GetMapping("/{id}")
    @RequiresPermission(module = SHIFTS, action = VIEW)
    public ResponseEntity<ApiResponse<ShiftDTO>> getShift(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(shiftService.getShiftById(id)));
    }

    @PostMapping
    @RequiresPermission(module = SHIFTS, action = MODIFY)
    public ResponseEntity<ApiResponse<ShiftDTO>> createShift(@RequestBody ShiftDTO dto) {
        log.info("Creating shift: name='{}' start={} end={}", dto.getName(), dto.getStartTime(), dto.getEndTime());
        return ResponseEntity.ok(ApiResponse.success("Shift created", shiftService.createShift(dto)));
    }

    @PutMapping("/{id}")
    @RequiresPermission(module = SHIFTS, action = MODIFY)
    public ResponseEntity<ApiResponse<ShiftDTO>> updateShift(@PathVariable Long id, @RequestBody ShiftDTO dto) {
        log.info("Updating shift {}: name='{}' start={} end={}", id, dto.getName(), dto.getStartTime(), dto.getEndTime());
        return ResponseEntity.ok(ApiResponse.success("Shift updated", shiftService.updateShift(id, dto)));
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(module = SHIFTS, action = DELETE)
    public ResponseEntity<ApiResponse<Void>> deleteShift(@PathVariable Long id) {
        shiftService.deleteShift(id);
        return ResponseEntity.ok(ApiResponse.success("Shift deleted", null));
    }

    /**
     * Permanently delete a shift. This removes the shift from all groups and deletes the record.
     * This action cannot be undone.
     */
    @DeleteMapping("/{id}/permanent")
    @RequiresPermission(module = SHIFTS, action = DELETE)
    public ResponseEntity<ApiResponse<Void>> permanentlyDeleteShift(@PathVariable Long id) {
        try {
            shiftService.permanentlyDeleteShift(id);
            return ResponseEntity.ok(ApiResponse.success("Shift permanently deleted", null));
        } catch (Exception e) {
            log.warn("Permanent delete failed for shift {}: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Delete failed: " + e.getMessage()));
        }
    }
}
