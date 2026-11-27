package com.attendance.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** Request and response bodies of the user / role administration API. */
public final class SecurityDtos {

    private SecurityDtos() {
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserDTO {
        private Long id;
        private String username;
        private String fullName;
        private String email;
        private boolean enabled;
        private boolean superAdmin;
        private boolean mustChangePassword;
        private boolean locked;
        private LocalDateTime lockedUntil;
        private LocalDateTime lastLoginAt;
        private LocalDateTime createdAt;
        private String createdBy;
        private List<Long> roleIds;
        private List<String> roleNames;
        /** Whether the current user may edit / reset / delete this account. */
        private boolean manageable;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UserForm {
        /** Only used when creating; usernames cannot be changed. */
        @Pattern(regexp = "^[A-Za-z0-9._-]{3,60}$", message = "{validation.username}")
        private String username;

        @Size(max = 120)
        private String fullName;

        @Email
        @Size(max = 150)
        private String email;

        /** Only used when creating. */
        @Size(max = 200)
        private String password;

        private boolean enabled = true;

        private boolean superAdmin;

        private List<Long> roleIds = new ArrayList<>();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleGrantDTO {
        private String module;
        private List<String> actions;
        private boolean allGroups;
        private List<Long> groupIds;
        private List<String> groupNames;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleDTO {
        private Long id;
        private String name;
        private String description;
        private List<RoleGrantDTO> grants;
        private int userCount;
        /** Whether the current user may edit / delete / assign this role. */
        private boolean manageable;
        private LocalDateTime updatedAt;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleGrantForm {
        @NotBlank
        private String module;

        @NotEmpty
        private List<String> actions = new ArrayList<>();

        private boolean allGroups;

        private List<Long> groupIds = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoleForm {
        @NotBlank
        @Size(max = 100)
        private String name;

        @Size(max = 500)
        private String description;

        @Valid
        private List<RoleGrantForm> grants = new ArrayList<>();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PasswordChangeRequest {
        @NotBlank
        private String currentPassword;

        @NotBlank
        @Size(max = 200)
        private String newPassword;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PasswordResetRequest {
        @NotBlank
        @Size(max = 200)
        private String newPassword;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuditLogDTO {
        private Long id;
        private LocalDateTime eventTime;
        private String actor;
        private String action;
        private String target;
        private String details;
        private String ipAddress;
        private boolean success;
    }

    /** A module as shown in the role editor, with what the current user may hand out on it. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModuleInfoDTO {
        private String module;
        private String label;
        private boolean groupScoped;
        /** Actions the current user may include in a role for this module. */
        private List<String> grantableActions;
        /** True when the user may grant on every group; otherwise only {@link #grantableGroupIds}. */
        private boolean grantableAllGroups;
        private List<Long> grantableGroupIds;
    }

    /** The signed-in user, for the browser. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CurrentUserDTO {
        private String username;
        private String fullName;
        private boolean superAdmin;
        private boolean mustChangePassword;
        private Object permissions;
    }
}
