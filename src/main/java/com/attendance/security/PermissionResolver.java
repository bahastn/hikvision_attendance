package com.attendance.security;

import com.attendance.entity.AppRole;
import com.attendance.entity.AppUser;
import com.attendance.entity.EmployeeGroup;
import com.attendance.entity.RoleGrant;
import com.attendance.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a user's {@link EffectivePermissions} from their roles.
 * <p>
 * Results are cached for a few seconds because the dashboard polls several
 * endpoints every two seconds. Any change to users or roles clears the cache,
 * so revoking a permission takes effect on the very next request.
 */
@Component
@RequiredArgsConstructor
public class PermissionResolver {

    private static final long CACHE_MILLIS = 5_000;

    private final AppUserRepository userRepository;

    private final Map<String, CachedEntry> cache = new ConcurrentHashMap<>();

    private record CachedEntry(Optional<EffectivePermissions> permissions, long expiresAt) {
    }

    /** Permissions of an enabled user, or empty when the user no longer exists or is disabled. */
    @Transactional(readOnly = true)
    public Optional<EffectivePermissions> resolve(String username) {
        if (username == null) {
            return Optional.empty();
        }
        String key = username.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        CachedEntry cached = cache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return cached.permissions();
        }
        Optional<EffectivePermissions> computed = userRepository.findByUsernameIgnoreCase(username)
                .filter(AppUser::isEnabled)
                .map(PermissionResolver::compute);
        cache.put(key, new CachedEntry(computed, now + CACHE_MILLIS));
        return computed;
    }

    public void invalidateAll() {
        cache.clear();
    }

    /**
     * Clear the cache once the current transaction commits (and immediately if there is none).
     * This avoids a window where a concurrent request repopulates the cache from
     * not-yet-committed data and keeps the old permissions alive.
     */
    public void invalidateOnCommit() {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            cache.clear();
                        }
                    });
        } else {
            cache.clear();
        }
    }

    static EffectivePermissions compute(AppUser user) {
        Map<AppModule, Map<PermissionAction, GroupScope>> scopes = new EnumMap<>(AppModule.class);
        if (!user.isSuperAdmin()) {
            for (AppRole role : user.getRoles()) {
                for (RoleGrant grant : role.getGrants()) {
                    GroupScope grantScope = scopeOf(grant);
                    if (grantScope.isNone()) {
                        continue;
                    }
                    Map<PermissionAction, GroupScope> byAction =
                            scopes.computeIfAbsent(grant.getModule(), m -> new EnumMap<>(PermissionAction.class));
                    for (PermissionAction action : withImplied(grant.getActions())) {
                        byAction.merge(action, grantScope, GroupScope::union);
                    }
                }
            }
        }
        return new EffectivePermissions(user.getId(), user.getUsername(), user.getFullName(),
                user.isSuperAdmin(), user.isMustChangePassword(), user.getCredentialsChangedAt(), scopes);
    }

    /** The groups a single grant row covers. Organisation-wide modules always cover everything. */
    public static GroupScope scopeOf(RoleGrant grant) {
        if (!grant.getModule().isGroupScoped() || grant.isAllGroups()) {
            return GroupScope.ALL;
        }
        List<Long> ids = new ArrayList<>();
        for (EmployeeGroup g : grant.getGroups()) {
            ids.add(g.getId());
        }
        return GroupScope.of(ids);
    }

    /** MODIFY, DELETE and GRANT each include VIEW. */
    public static Set<PermissionAction> withImplied(Collection<PermissionAction> actions) {
        EnumSet<PermissionAction> result = EnumSet.noneOf(PermissionAction.class);
        if (actions != null) {
            result.addAll(actions);
        }
        if (!result.isEmpty()) {
            result.add(PermissionAction.VIEW);
        }
        return result;
    }
}
