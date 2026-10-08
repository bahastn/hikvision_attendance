package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.LeaveDTO;
import com.attendance.service.LeaveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/leaves")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class LeaveRestController {

    private final LeaveService leaveService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<LeaveDTO>>> getAllLeaves() {
        return ResponseEntity.ok(ApiResponse.success(leaveService.getAllLeaves()));
    }

    @GetMapping("/status/{status}")
    public ResponseEntity<ApiResponse<List<LeaveDTO>>> getLeavesByStatus(@PathVariable String status) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.getLeavesByStatus(status)));
    }

    @GetMapping("/type/{type}")
    public ResponseEntity<ApiResponse<List<LeaveDTO>>> getLeavesByType(@PathVariable String type) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.getLeavesByType(type)));
    }

    @GetMapping("/active-today")
    public ResponseEntity<ApiResponse<List<LeaveDTO>>> getActiveLeavesToday() {
        return ResponseEntity.ok(ApiResponse.success(leaveService.getActiveLeavesToday()));
    }

    @GetMapping("/upcoming-active")
    public ResponseEntity<ApiResponse<List<LeaveDTO>>> getUpcomingAndActiveLeaves() {
        return ResponseEntity.ok(ApiResponse.success(leaveService.getUpcomingAndActiveLeaves()));
    }

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<LeaveDTO>>> getPendingLeaves() {
        return ResponseEntity.ok(ApiResponse.success(leaveService.getPendingLeaves()));
    }

    @GetMapping("/employee/{employeeId}")
    public ResponseEntity<ApiResponse<List<LeaveDTO>>> getLeavesByEmployee(@PathVariable Long employeeId) {
        return ResponseEntity.ok(ApiResponse.success(leaveService.getLeavesByEmployee(employeeId)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<LeaveDTO>> createLeave(@RequestBody LeaveDTO dto) {
        log.info("Creating leave for employee: {} type: {} from: {} to: {}",
                dto.getEmployeeId(), dto.getLeaveType(), dto.getStartDate(), dto.getEndDate());
        return ResponseEntity.ok(ApiResponse.success("Leave request created", leaveService.createLeave(dto)));
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<ApiResponse<LeaveDTO>> approveLeave(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Leave approved", leaveService.approveLeave(id, "Admin")));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<ApiResponse<LeaveDTO>> rejectLeave(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Leave rejected", leaveService.rejectLeave(id, "Admin")));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<LeaveDTO>> cancelLeave(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success("Leave cancelled", leaveService.cancelLeave(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteLeave(@PathVariable Long id) {
        leaveService.deleteLeave(id);
        return ResponseEntity.ok(ApiResponse.success("Leave deleted", null));
    }
}
