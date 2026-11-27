package com.attendance.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Used from Thymeleaf as {@code ${@perm.canView('EMPLOYEES')}} to show only the
 * menu entries a user can open. Purely cosmetic: the server enforces everything.
 */
@Component("perm")
@RequiredArgsConstructor
public class PermissionViewHelper {

    private final AccessControlService access;
    private final ObjectMapper objectMapper;

    public boolean can(String module, String action) {
        try {
            return access.has(AppModule.valueOf(module), PermissionAction.valueOf(action));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public boolean canView(String module) {
        return can(module, "VIEW");
    }

    /** True when the user can view at least one of the given modules (for menu sections). */
    public boolean canViewAny(String... modules) {
        for (String m : modules) {
            if (canView(m)) {
                return true;
            }
        }
        return false;
    }

    public boolean isSuperAdmin() {
        return access.currentPermissions().map(EffectivePermissions::superAdmin).orElse(false);
    }

    public String username() {
        return access.currentPermissions().map(EffectivePermissions::username).orElse("");
    }

    public String displayName() {
        return access.currentPermissions()
                .map(p -> p.fullName() != null && !p.fullName().isBlank() ? p.fullName() : p.username())
                .orElse("");
    }

    public String initial() {
        String name = displayName();
        return name.isEmpty() ? "?" : name.substring(0, 1).toUpperCase();
    }

    /** JSON for {@code window.currentUser} in the page, so scripts can hide buttons the user cannot use. */
    public String clientJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        access.currentPermissions().ifPresent(p -> {
            json.put("username", p.username());
            json.put("superAdmin", p.superAdmin());
            json.put("permissions", p.toClientMap());
        });
        try {
            return objectMapper.writeValueAsString(json);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
