package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.EmployeeGroupDTO;
import com.attendance.security.AccessControlService;
import com.attendance.security.GroupScope;
import com.attendance.security.RequiresPermission;
import com.attendance.service.EmployeeGroupService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static com.attendance.security.AppModule.*;
import static com.attendance.security.PermissionAction.*;

@RestController
@RequestMapping("/api/groups")
@RequiredArgsConstructor
@Slf4j
public class EmployeeGroupRestController {

    private final EmployeeGroupService groupService;
    private final AccessControlService access;

    /** Group list; also the group pick-list of the Employees, Leaves, Attendance, Schedule and Roles pages. */
    @GetMapping
    @RequiresPermission(module = GROUPS, action = VIEW)
    @RequiresPermission(module = EMPLOYEES, action = VIEW)
    @RequiresPermission(module = ATTENDANCE, action = VIEW)
    @RequiresPermission(module = LEAVES, action = VIEW)
    @RequiresPermission(module = SHIFT_SCHEDULE, action = VIEW)
    @RequiresPermission(module = USER_MANAGEMENT, action = VIEW)
    public ResponseEntity<ApiResponse<List<EmployeeGroupDTO>>> getAllGroups() {
        return ResponseEntity.ok(ApiResponse.success(
                access.filter(groupService.getAllGroups(), lookupScope(), EmployeeGroupDTO::getId)));
    }

    @GetMapping("/active")
    @RequiresPermission(module = GROUPS, action = VIEW)
    @RequiresPermission(module = EMPLOYEES, action = VIEW)
    @RequiresPermission(module = ATTENDANCE, action = VIEW)
    @RequiresPermission(module = LEAVES, action = VIEW)
    @RequiresPermission(module = SHIFT_SCHEDULE, action = VIEW)
    @RequiresPermission(module = USER_MANAGEMENT, action = VIEW)
    public ResponseEntity<ApiResponse<List<EmployeeGroupDTO>>> getActiveGroups() {
        return ResponseEntity.ok(ApiResponse.success(
                access.filter(groupService.getActiveGroups(), lookupScope(), EmployeeGroupDTO::getId)));
    }

    @GetMapping("/{id}")
    @RequiresPermission(module = GROUPS, action = VIEW)
    public ResponseEntity<ApiResponse<EmployeeGroupDTO>> getGroup(@PathVariable Long id) {
        access.requireGroup(GROUPS, VIEW, id);
        return ResponseEntity.ok(ApiResponse.success(groupService.getGroupById(id)));
    }

    /** A new group is outside every limited scope, so creating one needs "all groups". */
    @PostMapping
    @RequiresPermission(module = GROUPS, action = MODIFY)
    public ResponseEntity<ApiResponse<EmployeeGroupDTO>> createGroup(@RequestBody EmployeeGroupDTO dto) {
        access.requireGroup(GROUPS, MODIFY, null);
        log.info("Creating group: name='{}' shiftId={} by {}", dto.getName(), dto.getShiftId(), access.currentUsername());
        return ResponseEntity.ok(ApiResponse.success("Group created", groupService.createGroup(dto)));
    }

    @PutMapping("/{id}")
    @RequiresPermission(module = GROUPS, action = MODIFY)
    public ResponseEntity<ApiResponse<EmployeeGroupDTO>> updateGroup(@PathVariable Long id, @RequestBody EmployeeGroupDTO dto) {
        access.requireGroup(GROUPS, MODIFY, id);
        if (dto.getEmployeeIds() != null) {
            // Moving employees into this group changes which group governs their
            // attendance and leave data, so require employee-modify rights on both
            // the destination group and each employee's current group.
            access.requireGroup(EMPLOYEES, MODIFY, id);
            access.requireEmployees(EMPLOYEES, MODIFY, dto.getEmployeeIds());
        }
        log.info("Updating group {}: name='{}' shiftId={} by {}", id, dto.getName(), dto.getShiftId(), access.currentUsername());
        return ResponseEntity.ok(ApiResponse.success("Group updated", groupService.updateGroup(id, dto)));
    }

    /** Deactivates the group and removes its employees from it. */
    @DeleteMapping("/{id}")
    @RequiresPermission(module = GROUPS, action = DELETE)
    public ResponseEntity<ApiResponse<Void>> deleteGroup(@PathVariable Long id) {
        access.requireGroup(GROUPS, DELETE, id);
        groupService.deleteGroup(id);
        return ResponseEntity.ok(ApiResponse.success("Group deleted", null));
    }

    /**
     * Permanently delete a group. This removes all employee assignments and deletes the record.
     * This action cannot be undone.
     */
    @DeleteMapping("/{id}/permanent")
    @RequiresPermission(module = GROUPS, action = DELETE)
    public ResponseEntity<ApiResponse<Void>> permanentlyDeleteGroup(@PathVariable Long id) {
        access.requireGroup(GROUPS, DELETE, id);
        try {
            groupService.permanentlyDeleteGroup(id);
            return ResponseEntity.ok(ApiResponse.success("Group permanently deleted", null));
        } catch (Exception e) {
            log.warn("Permanent delete failed for group {}: {}", id, e.getMessage());
            return ResponseEntity.ok(ApiResponse.error("Delete failed: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/assign-employees")
    @RequiresPermission(module = GROUPS, action = MODIFY)
    public ResponseEntity<ApiResponse<Void>> assignEmployees(@PathVariable Long id, @RequestBody List<Long> employeeIds) {
        access.requireGroup(GROUPS, MODIFY, id);
        access.requireGroup(EMPLOYEES, MODIFY, id);
        access.requireEmployees(EMPLOYEES, MODIFY, employeeIds);
        groupService.assignEmployeesToGroup(id, employeeIds);
        return ResponseEntity.ok(ApiResponse.success("Employees assigned to group", null));
    }

    /** Groups visible in pick-lists: the union of what the user can view in any group-based module. */
    private GroupScope lookupScope() {
        if (access.has(USER_MANAGEMENT, VIEW)) {
            return GroupScope.ALL; // the role editor needs every group name
        }
        return access.current().unionScope(VIEW, GROUPS, EMPLOYEES, ATTENDANCE, LEAVES, SHIFT_SCHEDULE);
    }
}
