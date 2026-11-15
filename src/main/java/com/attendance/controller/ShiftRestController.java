package com.attendance.controller;

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
@CrossOrigin(origins = "*")
public class ShiftRestController {

    private final ShiftService shiftService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ShiftDTO>>> getAllShifts() {
        return ResponseEntity.ok(ApiResponse.success(shiftService.getAllShifts()));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<ShiftDTO>>> getActiveShifts() {
        return ResponseEntity.ok(ApiResponse.success(shiftService.getActiveShifts()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ShiftDTO>> getShift(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(shiftService.getShiftById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ShiftDTO>> createShift(@RequestBody ShiftDTO dto) {
        log.info("Creating shift: name='{}' start={} end={}", dto.getName(), dto.getStartTime(), dto.getEndTime());
        return ResponseEntity.ok(ApiResponse.success("Shift created", shiftService.createShift(dto)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ShiftDTO>> updateShift(@PathVariable Long id, @RequestBody ShiftDTO dto) {
        log.info("Updating shift {}: name='{}' start={} end={}", id, dto.getName(), dto.getStartTime(), dto.getEndTime());
        return ResponseEntity.ok(ApiResponse.success("Shift updated", shiftService.updateShift(id, dto)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteShift(@PathVariable Long id) {
        shiftService.deleteShift(id);
        return ResponseEntity.ok(ApiResponse.success("Shift deleted", null));
    }

    /**
     * Permanently delete a shift. This removes the shift from all groups and deletes the record.
     * This action cannot be undone.
     */
    @DeleteMapping("/{id}/permanent")
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
