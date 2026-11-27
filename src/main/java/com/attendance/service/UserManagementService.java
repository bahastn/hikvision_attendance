package com.attendance.service;

import com.attendance.dto.SecurityDtos.*;
import com.attendance.entity.*;
import com.attendance.repository.AppRoleRepository;
import com.attendance.repository.AppUserRepository;
import com.attendance.repository.EmployeeGroupRepository;
import com.attendance.security.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Users and roles administration.
 * <p>
 * Anti-escalation rules (for everyone except super admins):
 * <ul>
 *   <li>A permission can only be put into a role by someone who holds GRANT on that module
 *       <b>and</b> holds each of those actions themselves, for at least the same groups.</li>
 *   <li>A role is "manageable" (edit, delete, assign) only when every grant in it passes that rule.</li>
 *   <li>A user is "manageable" (edit, reset password, unlock, delete) only when they are not a
 *       super admin and every role they hold is manageable.</li>
 *   <li>Only super admins create super admins; the last enabled super admin cannot be removed.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class UserManagementService {

    private final AppUserRepository userRepository;
    private final AppRoleRepository roleRepository;
    private final EmployeeGroupRepository groupRepository;
    private final AccessControlService access;
    private final PermissionResolver permissionResolver;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final SecurityAuditService audit;
    private final MessageSource messages;

    // =================================================================== users

    @Transactional(readOnly = true)
    public List<UserDTO> listUsers() {
        access.require(AppModule.USER_MANAGEMENT, PermissionAction.VIEW);
        EffectivePermissions actor = access.current();
        return userRepository.findAllByOrderByUsernameAsc().stream()
                .map(u -> toUserDTO(u, actor))
                .toList();
    }

    @Transactional
    public UserDTO createUser(UserForm form) {
        access.require(AppModule.USER_MANAGEMENT, PermissionAction.MODIFY);
        EffectivePermissions actor = access.current();

        String username = form.getUsername() == null ? "" : form.getUsername().trim();
        if (!username.matches("^[A-Za-z0-9._-]{3,60}$")) {
            throw new IllegalArgumentException(msg("validation.username"));
        }
        if (userRepository.existsByUsernameIgnoreCase(username)) {
            throw new IllegalArgumentException(msg("users.error.usernameTaken"));
        }
        if (form.isSuperAdmin() && !actor.superAdmin()) {
            throw new AccessDeniedException(msg("users.error.onlySuperAdminCanGrantSuper"));
        }
        requirePasswordOk(form.getPassword(), username);

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setFullName(trimToNull(form.getFullName()));
        user.setEmail(trimToNull(form.getEmail()));
        user.setEnabled(form.isEnabled());
        user.setSuperAdmin(form.isSuperAdmin());
        user.setPasswordHash(passwordEncoder.encode(form.getPassword()));
        user.setMustChangePassword(true);
        user.setCreatedBy(actor.username());
        user.setRoles(new HashSet<>(loadAssignableRoles(form.getRoleIds(), actor)));
        AppUser saved = userRepository.save(user);

        permissionResolver.invalidateOnCommit();
        audit.record("USER_CREATED", saved.getUsername(), describeUser(saved), true);
        return toUserDTO(saved, actor);
    }

    @Transactional
    public UserDTO updateUser(Long id, UserForm form) {
        access.require(AppModule.USER_MANAGEMENT, PermissionAction.MODIFY);
        EffectivePermissions actor = access.current();
        AppUser user = findUser(id);
        requireManageable(user, actor);

        boolean self = user.getId().equals(actor.userId());
        if (self && !form.isEnabled()) {
            throw new IllegalArgumentException(msg("users.error.cannotDisableSelf"));
        }
        if (form.isSuperAdmin() != user.isSuperAdmin() && !actor.superAdmin()) {
            throw new AccessDeniedException(msg("users.error.onlySuperAdminCanGrantSuper"));
        }
        boolean losesSuperAdmin = user.isSuperAdmin() && user.isEnabled() && (!form.isSuperAdmin() || !form.isEnabled());
        if (losesSuperAdmin && userRepository.countBySuperAdminTrueAndEnabledTrue() <= 1) {
            throw new IllegalArgumentException(msg("users.error.lastSuperAdmin"));
        }

        String before = describeUser(user);
        boolean disabledNow = user.isEnabled() && !form.isEnabled();

        user.setFullName(trimToNull(form.getFullName()));
        user.setEmail(trimToNull(form.getEmail()));
        user.setEnabled(form.isEnabled());
        user.setSuperAdmin(form.isSuperAdmin());
        user.setRoles(new HashSet<>(loadAssignableRoles(form.getRoleIds(), actor)));
        if (disabledNow) {
            user.setCredentialsChangedAt(LocalDateTime.now()); // ends their open sessions
        }
        AppUser saved = userRepository.save(user);

        permissionResolver.invalidateOnCommit();
        audit.record("USER_UPDATED", saved.getUsername(), "Before: " + before + " | After: " + describeUser(saved), true);
        return toUserDTO(saved, actor);
    }

    @Transactional
    public void resetPassword(Long id, String newPassword) {
        access.require(AppModule.USER_MANAGEMENT, PermissionAction.MODIFY);
        EffectivePermissions actor = access.current();
        AppUser user = findUser(id);
        requireManageable(user, actor);
        if (user.getId().equals(actor.userId())) {
            // Resetting your own password here would skip the current-password check;
            // use the change-password page (/api/account/password) instead.
            throw new IllegalArgumentException(msg("users.error.cannotResetSelf"));
        }
        requirePasswordOk(newPassword, user.getUsername());

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(true);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        user.setCredentialsChangedAt(LocalDateTime.now()); // signs the user out everywhere
        userRepository.save(user);

        permissionResolver.invalidateOnCommit();
        audit.record("PASSWORD_RESET", user.getUsername(), "Temporary password set; change required at next sign-in", true);
    }

    @Transactional
    public void unlockUser(Long id) {
        access.require(AppModule.USER_MANAGEMENT, PermissionAction.MODIFY);
        EffectivePermissions actor = access.current();
        AppUser user = findUser(id);
        requireManageable(user, actor);
        user.setFailedLoginAttempts(0);
        user.setLockedUntil(null);
        userRepository.save(user);
        audit.record("USER_UNLOCKED", user.getUsername(), null, true);
    }

    @Transactional
    public void deleteUser(Long id) {
        access.require(AppModule.USER_MANAGEMENT, PermissionAction.DELETE);
        EffectivePermissions actor = access.current();
        AppUser user = findUser(id);
        requireManageable(user, actor);
        if (user.getId().equals(actor.userId())) {
            throw new IllegalArgumentException(msg("users.error.cannotDeleteSelf"));
        }
        if (user.isSuperAdmin() && user.isEnabled() && userRepository.countBySuperAdminTrueAndEnabledTrue() <= 1) {
            throw new IllegalArgumentException(msg("users.error.lastSuperAdmin"));
        }
        String description = describeUser(user);
        userRepository.delete(user);
        permissionResolver.invalidateOnCommit();
        audit.record("USER_DELETED", user.getUsername(), description, true);
    }

    /** The signed-in user changes their own password (also used for the forced first change). */
    @Transactional
    public LocalDateTime changeOwnPassword(String currentPassword, String newPassword) {
        EffectivePermissions actor = access.current();
        AppUser user = findUser(actor.userId());
        if (!passwordEncoder.matches(currentPassword == null ? "" : currentPassword, user.getPasswordHash())) {
            audit.record("PASSWORD_CHANGE_FAILED", user.getUsername(), "Current password was wrong", false);
            throw new IllegalArgumentException(msg("account.password.currentWrong"));
        }
        if (passwordEncoder.matches(newPassword == null ? "" : newPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException(msg("account.password.sameAsOld"));
        }
        requirePasswordOk(newPassword, user.getUsername());

        // Truncate to milliseconds so the value stored in the database round-trips exactly
        // to what we stamp on this session; otherwise the own session could be seen as
        // pre-dating the change and be ended immediately.
        LocalDateTime changedAt = LocalDateTime.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setMustChangePassword(false);
        user.setCredentialsChangedAt(changedAt); // other sessions of this user end
        userRepository.save(user);
        permissionResolver.invalidateOnCommit();
        audit.record("PASSWORD_CHANGED", user.getUsername(), null, true);
        // The caller keeps this session alive by stamping it with exactly this time.
        return changedAt;
    }

    // =================================================================== roles

    @Transactional(readOnly = true)
    public List<RoleDTO> listRoles() {
        access.require(AppModule.USER_MANAGEMENT, PermissionAction.VIEW);
        EffectivePermissions actor = access.current();
        Map<Long, Integer> userCounts = new HashMap<>();
        for (AppUser u : userRepository.findAll()) {
            for (AppRole r : u.getRoles()) {
                userCounts.merge(r.getId(), 1, Integer::sum);
            }
        }
        return roleRepository.findAllByOrderByNameAsc().stream()
                .map(r -> toRoleDTO(r, actor, userCounts.getOrDefault(r.getId(), 0)))
                .toList();
    }

    @Transactional(readOnly = true)
    public RoleDTO getRole(Long id) {
        access.require(AppModule.USER_MANAGEMENT, PermissionAction.VIEW);
        AppRole role = findRole(id);
        return toRoleDTO(role, access.current(), userRepository.findByRoleId(id).size());
    }

    @Transactional
    public RoleDTO createRole(RoleForm form) {
        access.require(AppModule.USER_MANAGEMENT, PermissionAction.MODIFY);
        EffectivePermissions actor = access.current();
        String name = requireRoleName(form.getName(), null);

        AppRole role = new AppRole();
        role.setName(name);
        role.setDescription(trimToNull(form.getDescription()));
        role.setCreatedBy(actor.username());
        applyGrants(role, form.getGrants(), actor);
        AppRole saved = roleRepository.save(role);

        permissionResolver.invalidateOnCommit();
        audit.record("ROLE_CREATED", saved.getName(), describeRole(saved), true);
        return toRoleDTO(saved, actor, 0);
    }

    @Transactional
    public RoleDTO updateRole(Long id, RoleForm form) {
        access.require(AppModule.USER_MANAGEMENT, PermissionAction.MODIFY);
        EffectivePermissions actor = access.current();
        AppRole role = findRole(id);
        if (!isRoleManageable(role, actor)) {
            throw new AccessDeniedException(msg("roles.error.notManageable"));
        }
        requireHoldersManageable(id, actor);
        String before = describeRole(role);
        role.setName(requireRoleName(form.getName(), id));
        role.setDescription(trimToNull(form.getDescription()));
        applyGrants(role, form.getGrants(), actor);
        AppRole saved = roleRepository.save(role);

        permissionResolver.invalidateOnCommit();
        audit.record("ROLE_UPDATED", saved.getName(), "Before: " + before + " | After: " + describeRole(saved), true);
        return toRoleDTO(saved, actor, userRepository.findByRoleId(id).size());
    }

    @Transactional
    public void deleteRole(Long id) {
        access.require(AppModule.USER_MANAGEMENT, PermissionAction.DELETE);
        EffectivePermissions actor = access.current();
        AppRole role = findRole(id);
        if (!isRoleManageable(role, actor)) {
            throw new AccessDeniedException(msg("roles.error.notManageable"));
        }
        requireHoldersManageable(id, actor);
        String description = describeRole(role);
        for (AppUser user : userRepository.findByRoleId(id)) {
            user.getRoles().remove(role);
            userRepository.save(user);
        }
        roleRepository.delete(role);
        permissionResolver.invalidateOnCommit();
        audit.record("ROLE_DELETED", role.getName(), description, true);
    }

    /** Modules for the role editor, with what the current user may hand out on each. */
    @Transactional(readOnly = true)
    public List<ModuleInfoDTO> moduleCatalog() {
        access.require(AppModule.USER_MANAGEMENT, PermissionAction.VIEW);
        EffectivePermissions actor = access.current();
        List<ModuleInfoDTO> result = new ArrayList<>();
        for (AppModule module : AppModule.values()) {
            GroupScope grantScope = actor.scope(module, PermissionAction.GRANT);
            List<String> grantable = new ArrayList<>();
            if (!grantScope.isNone()) {
                for (PermissionAction action : PermissionAction.values()) {
                    if (!actor.scope(module, action).isNone()) {
                        grantable.add(action.name());
                    }
                }
            }
            // The groups the actor can grant every listed action on = intersection is approximated by
            // the GRANT scope; the server re-validates each row on save.
            result.add(ModuleInfoDTO.builder()
                    .module(module.name())
                    .label(msg(module.messageKey()))
                    .groupScoped(module.isGroupScoped())
                    .grantableActions(grantable)
                    .grantableAllGroups(grantScope.isAll())
                    .grantableGroupIds(new ArrayList<>(new TreeSet<>(grantScope.groupIds())))
                    .build());
        }
        return result;
    }

    @Transactional(readOnly = true)
    public List<AuditLogDTO> auditLog(int limit) {
        access.require(AppModule.USER_MANAGEMENT, PermissionAction.VIEW);
        return audit.latest(limit).stream()
                .map(a -> AuditLogDTO.builder()
                        .id(a.getId())
                        .eventTime(a.getEventTime())
                        .actor(a.getActor())
                        .action(a.getAction())
                        .target(a.getTarget())
                        .details(a.getDetails())
                        .ipAddress(a.getIpAddress())
                        .success(a.isSuccess())
                        .build())
                .toList();
    }

    // =================================================================== delegation rules

    /** Whether {@code actor} may hand out these actions on this module for these groups. */
    static boolean canDelegate(EffectivePermissions actor, AppModule module,
                               Set<PermissionAction> actions, GroupScope scope) {
        if (actor.superAdmin()) {
            return true;
        }
        GroupScope wanted = module.isGroupScoped() ? scope : GroupScope.ALL;
        if (!actor.scope(module, PermissionAction.GRANT).covers(wanted)) {
            return false;
        }
        for (PermissionAction action : PermissionResolver.withImplied(actions)) {
            if (!actor.scope(module, action).covers(wanted)) {
                return false;
            }
        }
        return true;
    }

    boolean isRoleManageable(AppRole role, EffectivePermissions actor) {
        if (actor.superAdmin()) {
            return true;
        }
        for (RoleGrant grant : role.getGrants()) {
            if (!canDelegate(actor, grant.getModule(), grant.getActions(), PermissionResolver.scopeOf(grant))) {
                return false;
            }
        }
        return true;
    }

    boolean isUserManageable(AppUser user, EffectivePermissions actor) {
        if (actor.superAdmin()) {
            return true;
        }
        if (user.isSuperAdmin()) {
            return false;
        }
        return user.getRoles().stream().allMatch(r -> isRoleManageable(r, actor));
    }

    private void requireManageable(AppUser user, EffectivePermissions actor) {
        if (!isUserManageable(user, actor)) {
            throw new AccessDeniedException(msg("users.error.notManageable"));
        }
    }

    /**
     * Editing or deleting a role changes the permissions of everyone who holds it.
     * Only let the actor do that when they could also manage each of those users,
     * so a limited admin cannot alter a role shared with accounts above them.
     */
    private void requireHoldersManageable(Long roleId, EffectivePermissions actor) {
        if (actor.superAdmin()) {
            return;
        }
        for (AppUser holder : userRepository.findByRoleId(roleId)) {
            if (!isUserManageable(holder, actor)) {
                throw new AccessDeniedException(msg("roles.error.sharedWithHigher"));
            }
        }
    }

    private List<AppRole> loadAssignableRoles(List<Long> roleIds, EffectivePermissions actor) {
        if (roleIds == null || roleIds.isEmpty()) {
            return List.of();
        }
        List<AppRole> roles = roleRepository.findAllById(new LinkedHashSet<>(roleIds));
        if (roles.size() != new HashSet<>(roleIds).size()) {
            throw new IllegalArgumentException(msg("roles.error.notFound"));
        }
        for (AppRole role : roles) {
            if (!isRoleManageable(role, actor)) {
                throw new AccessDeniedException(msg("roles.error.cannotAssign", role.getName()));
            }
        }
        return roles;
    }

    private void applyGrants(AppRole role, List<RoleGrantForm> forms, EffectivePermissions actor) {
        List<RoleGrant> newGrants = new ArrayList<>();
        for (RoleGrantForm form : forms == null ? List.<RoleGrantForm>of() : forms) {
            AppModule module = parseModule(form.getModule());
            Set<PermissionAction> actions = parseActions(form.getActions());
            if (actions.isEmpty()) {
                throw new IllegalArgumentException(msg("roles.error.noActions", msg(module.messageKey())));
            }

            RoleGrant grant = new RoleGrant();
            grant.setRole(role);
            grant.setModule(module);
            grant.setActions(new HashSet<>(actions));
            if (!module.isGroupScoped() || form.isAllGroups()) {
                grant.setAllGroups(true);
            } else {
                Set<Long> ids = new LinkedHashSet<>(form.getGroupIds() == null ? List.of() : form.getGroupIds());
                ids.remove(null);
                if (ids.isEmpty()) {
                    throw new IllegalArgumentException(msg("roles.error.noGroups", msg(module.messageKey())));
                }
                List<EmployeeGroup> groups = groupRepository.findAllById(ids);
                if (groups.size() != ids.size()) {
                    throw new IllegalArgumentException(msg("roles.error.groupNotFound"));
                }
                grant.setAllGroups(false);
                grant.setGroups(new ArrayList<>(groups));
            }

            if (!canDelegate(actor, module, actions, PermissionResolver.scopeOf(grant))) {
                throw new AccessDeniedException(msg("roles.error.cannotGrant", msg(module.messageKey())));
            }
            newGrants.add(grant);
        }
        role.getGrants().clear();
        role.getGrants().addAll(newGrants);
    }

    private AppModule parseModule(String value) {
        try {
            return AppModule.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(msg("roles.error.unknownModule"));
        }
    }

    private Set<PermissionAction> parseActions(List<String> values) {
        EnumSet<PermissionAction> result = EnumSet.noneOf(PermissionAction.class);
        for (String v : values == null ? List.<String>of() : values) {
            try {
                result.add(PermissionAction.valueOf(v));
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException(msg("roles.error.unknownAction"));
            }
        }
        return result;
    }

    // =================================================================== helpers

    private AppUser findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(msg("users.error.notFound")));
    }

    private AppRole findRole(Long id) {
        return roleRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException(msg("roles.error.notFound")));
    }

    private String requireRoleName(String raw, Long currentId) {
        String name = raw == null ? "" : raw.trim();
        if (name.isEmpty() || name.length() > 100) {
            throw new IllegalArgumentException(msg("roles.error.nameRequired"));
        }
        roleRepository.findByNameIgnoreCase(name)
                .filter(existing -> !existing.getId().equals(currentId))
                .ifPresent(existing -> {
                    throw new IllegalArgumentException(msg("roles.error.nameTaken"));
                });
        return name;
    }

    private void requirePasswordOk(String password, String username) {
        String violation = passwordPolicy.violation(password, username);
        if (violation != null) {
            throw new IllegalArgumentException(msg(violation, passwordPolicy.minLength()));
        }
    }

    private UserDTO toUserDTO(AppUser u, EffectivePermissions actor) {
        List<AppRole> roles = u.getRoles().stream()
                .sorted(Comparator.comparing(AppRole::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        return UserDTO.builder()
                .id(u.getId())
                .username(u.getUsername())
                .fullName(u.getFullName())
                .email(u.getEmail())
                .enabled(u.isEnabled())
                .superAdmin(u.isSuperAdmin())
                .mustChangePassword(u.isMustChangePassword())
                .locked(u.isLockedNow())
                .lockedUntil(u.getLockedUntil())
                .lastLoginAt(u.getLastLoginAt())
                .createdAt(u.getCreatedAt())
                .createdBy(u.getCreatedBy())
                .roleIds(roles.stream().map(AppRole::getId).toList())
                .roleNames(roles.stream().map(AppRole::getName).toList())
                .manageable(isUserManageable(u, actor))
                .build();
    }

    private RoleDTO toRoleDTO(AppRole role, EffectivePermissions actor, int userCount) {
        List<RoleGrantDTO> grants = role.getGrants().stream()
                .sorted(Comparator.comparing(g -> g.getModule().ordinal()))
                .map(g -> RoleGrantDTO.builder()
                        .module(g.getModule().name())
                        .actions(g.getActions().stream().sorted().map(Enum::name).toList())
                        .allGroups(!g.getModule().isGroupScoped() || g.isAllGroups())
                        .groupIds(g.getGroups().stream().map(EmployeeGroup::getId).toList())
                        .groupNames(g.getGroups().stream().map(EmployeeGroup::getName).toList())
                        .build())
                .toList();
        return RoleDTO.builder()
                .id(role.getId())
                .name(role.getName())
                .description(role.getDescription())
                .grants(grants)
                .userCount(userCount)
                .manageable(isRoleManageable(role, actor))
                .updatedAt(role.getUpdatedAt())
                .build();
    }

    private static String describeUser(AppUser u) {
        return "enabled=" + u.isEnabled() + ", superAdmin=" + u.isSuperAdmin() + ", roles="
                + u.getRoles().stream().map(AppRole::getName).sorted().collect(Collectors.joining(",", "[", "]"));
    }

    private static String describeRole(AppRole r) {
        return r.getGrants().stream()
                .map(g -> g.getModule().name() + ":" + g.getActions().stream().sorted().map(Enum::name)
                        .collect(Collectors.joining("+"))
                        + "@" + (!g.getModule().isGroupScoped() || g.isAllGroups() ? "ALL"
                        : g.getGroups().stream().map(EmployeeGroup::getName).sorted().collect(Collectors.joining(","))))
                .collect(Collectors.joining("; ", "{", "}"));
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private String msg(String key, Object... args) {
        return messages.getMessage(key, args, key, LocaleContextHolder.getLocale());
    }
}
