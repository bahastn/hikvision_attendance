package com.attendance.security;

import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * The single place controllers and services ask "may the current user do this?".
 * <p>
 * Module-level checks answer whether the user holds an action at all;
 * group-level checks answer whether the specific record's employee group is
 * inside the user's scope for that action.
 */
@Service
@RequiredArgsConstructor
public class AccessControlService {

    private static final String REQUEST_CACHE_KEY = AccessControlService.class.getName() + ".permissions";

    private final PermissionResolver resolver;
    private final GroupOwnershipLookup ownership;
    private final MessageSource messages;

    // ------------------------------------------------------------------ current user

    /** Name of the signed-in user, or "system" for background jobs. */
    public String currentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return "system";
        }
        return auth.getName();
    }

    /** Permissions of the signed-in user, if any (cached for the duration of the request). */
    public Optional<EffectivePermissions> currentPermissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request != null) {
            Object cached = request.getAttribute(REQUEST_CACHE_KEY, RequestAttributes.SCOPE_REQUEST);
            if (cached instanceof EffectivePermissions p) {
                return Optional.of(p);
            }
        }
        Optional<EffectivePermissions> resolved = resolver.resolve(auth.getName());
        if (request != null) {
            resolved.ifPresent(p -> request.setAttribute(REQUEST_CACHE_KEY, p, RequestAttributes.SCOPE_REQUEST));
        }
        return resolved;
    }

    /** Permissions of the signed-in user; denies when nobody (or a disabled user) is signed in. */
    public EffectivePermissions current() {
        return currentPermissions().orElseThrow(() -> new AccessDeniedException(msg("access.denied.noSession")));
    }

    // ------------------------------------------------------------------ module-level

    public boolean has(AppModule module, PermissionAction action) {
        return currentPermissions().map(p -> p.has(module, action)).orElse(false);
    }

    public void require(AppModule module, PermissionAction action) {
        if (!current().has(module, action)) {
            throw denied(module, action, false);
        }
    }

    public GroupScope scope(AppModule module, PermissionAction action) {
        return current().scope(module, action);
    }

    // ------------------------------------------------------------------ group-level

    /** Deny unless {@code groupId} (null = no group) is inside the user's scope. */
    public void requireGroup(AppModule module, PermissionAction action, Long groupId) {
        GroupScope s = scope(module, action);
        if (!module.isGroupScoped()) {
            if (s.isNone()) throw denied(module, action, false);
            return;
        }
        if (!s.allows(groupId)) {
            throw denied(module, action, true);
        }
    }

    public void requireEmployee(AppModule module, PermissionAction action, Long employeeDbId) {
        requireGroup(module, action, ownership.groupOfEmployee(employeeDbId));
    }

    public void requireEmployeeCode(AppModule module, PermissionAction action, String employeeCode) {
        requireGroup(module, action, ownership.groupOfEmployeeCode(employeeCode));
    }

    public void requireEmployees(AppModule module, PermissionAction action, List<Long> employeeDbIds) {
        for (Long groupId : ownership.groupsOfEmployees(employeeDbIds)) {
            requireGroup(module, action, groupId);
        }
    }

    public void requireLeave(PermissionAction action, Long leaveId) {
        requireGroup(AppModule.LEAVES, action, ownership.groupOfLeave(leaveId));
    }

    public void requireAttendanceRecord(PermissionAction action, Long recordId) {
        requireGroup(AppModule.ATTENDANCE, action, ownership.groupOfAttendanceRecord(recordId));
    }

    public void requireSchedule(PermissionAction action, Long scheduleId) {
        requireGroup(AppModule.SHIFT_SCHEDULE, action, ownership.groupOfSchedule(scheduleId));
    }

    /** Keep only the items whose employee group is inside the user's scope. */
    public <T> List<T> filter(List<T> items, AppModule module, PermissionAction action, Function<T, Long> groupOf) {
        return filter(items, scope(module, action), groupOf);
    }

    public <T> List<T> filter(List<T> items, GroupScope scope, Function<T, Long> groupOf) {
        if (scope.isAll()) {
            return items;
        }
        if (scope.isNone() || items == null) {
            return List.of();
        }
        return items.stream().filter(item -> scope.allows(groupOf.apply(item))).toList();
    }

    // ------------------------------------------------------------------ messages

    public AccessDeniedException denied(AppModule module, PermissionAction action, boolean groupLevel) {
        String moduleName = msg(module.messageKey());
        String actionName = msg(action.messageKey());
        return new AccessDeniedException(msg(groupLevel ? "access.denied.group" : "access.denied.module",
                actionName, moduleName));
    }

    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
