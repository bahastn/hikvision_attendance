package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.EmployeeGroupDTO;
import com.attendance.service.EmployeeGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class EmployeeGroupRestController {

    private final EmployeeGroupService groupService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<EmployeeGroupDTO>>> getAllGroups() {
        return ResponseEntity.ok(ApiResponse.success(groupService.getAllGroups()));
    }

    @GetMapping("/active")
    public ResponseEntity<ApiResponse<List<EmployeeGroupDTO>>> getActiveGroups() {
        return ResponseEntity.ok(ApiResponse.success(groupService.getActiveGroups()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EmployeeGroupDTO>> getGroup(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(groupService.getGroupById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<EmployeeGroupDTO>> createGroup(@RequestBody EmployeeGroupDTO dto) {
        log.info("Creating group: name='{}' shiftId={}", dto.getName(), dto.getShiftId());
        return ResponseEntity.ok(ApiResponse.success("Group created", groupService.createGroup(dto)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<EmployeeGroupDTO>> updateGroup(@PathVariable Long id, @RequestBody EmployeeGroupDTO dto) {
        log.info("Updating group {}: name='{}' shiftId={}", id, dto.getName(), dto.getShiftId());
        return ResponseEntity.ok(ApiResponse.success("Group updated", groupService.updateGroup(id, dto)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deleteGroup(@PathVariable Long id) {
        groupService.deleteGroup(id);
        return ResponseEntity.ok(ApiResponse.success("Group deleted", null));
    }

    @PostMapping("/{id}/assign-employees")
    public ResponseEntity<ApiResponse<Void>> assignEmployees(@PathVariable Long id, @RequestBody List<Long> employeeIds) {
        groupService.assignEmployeesToGroup(id, employeeIds);
        return ResponseEntity.ok(ApiResponse.success("Employees assigned to group", null));
    }
}
