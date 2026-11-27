package com.attendance.security;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Everything one user may do, merged from all of their roles:
 * for every module and action, the employee groups it applies to.
 */
public final class EffectivePermissions {

    private final Long userId;
    private final String username;
    private final String fullName;
    private final boolean superAdmin;
    private final boolean mustChangePassword;
    private final LocalDateTime credentialsChangedAt;
    private final Map<AppModule, Map<PermissionAction, GroupScope>> scopes;

    public EffectivePermissions(Long userId, String username, String fullName, boolean superAdmin,
                                boolean mustChangePassword, LocalDateTime credentialsChangedAt,
                                Map<AppModule, Map<PermissionAction, GroupScope>> scopes) {
        this.userId = userId;
        this.username = username;
        this.fullName = fullName;
        this.superAdmin = superAdmin;
        this.mustChangePassword = mustChangePassword;
        this.credentialsChangedAt = credentialsChangedAt;
        Map<AppModule, Map<PermissionAction, GroupScope>> copy = new EnumMap<>(AppModule.class);
        scopes.forEach((module, byAction) -> copy.put(module, Collections.unmodifiableMap(new EnumMap<>(byAction))));
        this.scopes = Collections.unmodifiableMap(copy);
    }

    public Long userId() { return userId; }
    public String username() { return username; }
    public String fullName() { return fullName; }
    public boolean superAdmin() { return superAdmin; }
    public boolean mustChangePassword() { return mustChangePassword; }
    public LocalDateTime credentialsChangedAt() { return credentialsChangedAt; }

    /** The groups this user may perform {@code action} on inside {@code module}. */
    public GroupScope scope(AppModule module, PermissionAction action) {
        if (superAdmin) {
            return GroupScope.ALL;
        }
        Map<PermissionAction, GroupScope> byAction = scopes.get(module);
        if (byAction == null) {
            return GroupScope.NONE;
        }
        return byAction.getOrDefault(action, GroupScope.NONE);
    }

    /** True when the user may perform the action on at least some data of the module. */
    public boolean has(AppModule module, PermissionAction action) {
        return !scope(module, action).isNone();
    }

    /** Union of the scopes of several modules for one action (used by shared lookup endpoints). */
    public GroupScope unionScope(PermissionAction action, AppModule... modules) {
        GroupScope result = GroupScope.NONE;
        for (AppModule module : modules) {
            result = result.union(scope(module, action));
        }
        return result;
    }

    /**
     * JSON-friendly view for the browser: {@code {"EMPLOYEES": {"VIEW": {"all": false, "groups": [1,2]}}}}.
     * Only used to show/hide UI; the server re-checks everything.
     */
    public Map<String, Map<String, Object>> toClientMap() {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (AppModule module : AppModule.values()) {
            Map<String, Object> byAction = new LinkedHashMap<>();
            for (PermissionAction action : PermissionAction.values()) {
                GroupScope s = scope(module, action);
                if (!s.isNone()) {
                    Map<String, Object> scopeJson = new LinkedHashMap<>();
                    scopeJson.put("all", s.isAll());
                    scopeJson.put("groups", new TreeSet<>(s.groupIds()));
                    byAction.put(action.name(), scopeJson);
                }
            }
            if (!byAction.isEmpty()) {
                result.put(module.name(), byAction);
            }
        }
        return result;
    }
}
