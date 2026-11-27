package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import com.attendance.dto.SecurityDtos.*;
import com.attendance.security.AppModule;
import com.attendance.security.PermissionAction;
import com.attendance.security.RequiresPermission;
import com.attendance.service.UserManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Users, roles and the security audit log. Every method re-checks permissions in the service. */
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class SecurityAdminRestController {

    private final UserManagementService service;

    // ---- users

    @GetMapping("/users")
    @RequiresPermission(module = AppModule.USER_MANAGEMENT, action = PermissionAction.VIEW)
    public ResponseEntity<ApiResponse<List<UserDTO>>> listUsers() {
        return ResponseEntity.ok(ApiResponse.success(service.listUsers()));
    }

    @PostMapping("/users")
    @RequiresPermission(module = AppModule.USER_MANAGEMENT, action = PermissionAction.MODIFY)
    public ResponseEntity<ApiResponse<UserDTO>> createUser(@Valid @RequestBody UserForm form) {
        return ResponseEntity.ok(ApiResponse.success("User created", service.createUser(form)));
    }

    @PutMapping("/users/{id}")
    @RequiresPermission(module = AppModule.USER_MANAGEMENT, action = PermissionAction.MODIFY)
    public ResponseEntity<ApiResponse<UserDTO>> updateUser(@PathVariable Long id, @Valid @RequestBody UserForm form) {
        return ResponseEntity.ok(ApiResponse.success("User updated", service.updateUser(id, form)));
    }

    @PostMapping("/users/{id}/reset-password")
    @RequiresPermission(module = AppModule.USER_MANAGEMENT, action = PermissionAction.MODIFY)
    public ResponseEntity<ApiResponse<Void>> resetPassword(@PathVariable Long id,
                                                           @Valid @RequestBody PasswordResetRequest request) {
        service.resetPassword(id, request.getNewPassword());
        return ResponseEntity.ok(ApiResponse.success("Password reset", null));
    }

    @PostMapping("/users/{id}/unlock")
    @RequiresPermission(module = AppModule.USER_MANAGEMENT, action = PermissionAction.MODIFY)
    public ResponseEntity<ApiResponse<Void>> unlockUser(@PathVariable Long id) {
        service.unlockUser(id);
        return ResponseEntity.ok(ApiResponse.success("User unlocked", null));
    }

    @DeleteMapping("/users/{id}")
    @RequiresPermission(module = AppModule.USER_MANAGEMENT, action = PermissionAction.DELETE)
    public ResponseEntity<ApiResponse<Void>> deleteUser(@PathVariable Long id) {
        service.deleteUser(id);
        return ResponseEntity.ok(ApiResponse.success("User deleted", null));
    }

    // ---- roles

    @GetMapping("/roles")
    @RequiresPermission(module = AppModule.USER_MANAGEMENT, action = PermissionAction.VIEW)
    public ResponseEntity<ApiResponse<List<RoleDTO>>> listRoles() {
        return ResponseEntity.ok(ApiResponse.success(service.listRoles()));
    }

    @GetMapping("/roles/{id}")
    @RequiresPermission(module = AppModule.USER_MANAGEMENT, action = PermissionAction.VIEW)
    public ResponseEntity<ApiResponse<RoleDTO>> getRole(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponse.success(service.getRole(id)));
    }

    @PostMapping("/roles")
    @RequiresPermission(module = AppModule.USER_MANAGEMENT, action = PermissionAction.MODIFY)
    public ResponseEntity<ApiResponse<RoleDTO>> createRole(@Valid @RequestBody RoleForm form) {
        return ResponseEntity.ok(ApiResponse.success("Role created", service.createRole(form)));
    }

    @PutMapping("/roles/{id}")
    @RequiresPermission(module = AppModule.USER_MANAGEMENT, action = PermissionAction.MODIFY)
    public ResponseEntity<ApiResponse<RoleDTO>> updateRole(@PathVariable Long id, @Valid @RequestBody RoleForm form) {
        return ResponseEntity.ok(ApiResponse.success("Role updated", service.updateRole(id, form)));
    }

    @DeleteMapping("/roles/{id}")
    @RequiresPermission(module = AppModule.USER_MANAGEMENT, action = PermissionAction.DELETE)
    public ResponseEntity<ApiResponse<Void>> deleteRole(@PathVariable Long id) {
        service.deleteRole(id);
        return ResponseEntity.ok(ApiResponse.success("Role deleted", null));
    }

    @GetMapping("/modules")
    @RequiresPermission(module = AppModule.USER_MANAGEMENT, action = PermissionAction.VIEW)
    public ResponseEntity<ApiResponse<List<ModuleInfoDTO>>> modules() {
        return ResponseEntity.ok(ApiResponse.success(service.moduleCatalog()));
    }

    // ---- audit

    @GetMapping("/audit")
    @RequiresPermission(module = AppModule.USER_MANAGEMENT, action = PermissionAction.VIEW)
    public ResponseEntity<ApiResponse<List<AuditLogDTO>>> audit(@RequestParam(defaultValue = "200") int limit) {
        return ResponseEntity.ok(ApiResponse.success(service.auditLog(limit)));
    }
}
