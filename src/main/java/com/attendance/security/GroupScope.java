package com.attendance.security;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The employee groups a permission applies to: all groups, a fixed set of groups, or none.
 * <p>
 * Employees without a group are only covered by an "all groups" scope.
 */
public final class GroupScope {

    public static final GroupScope NONE = new GroupScope(false, Set.of());
    public static final GroupScope ALL = new GroupScope(true, Set.of());

    private final boolean all;
    private final Set<Long> groupIds;

    private GroupScope(boolean all, Set<Long> groupIds) {
        this.all = all;
        this.groupIds = groupIds;
    }

    public static GroupScope of(Collection<Long> groupIds) {
        if (groupIds == null || groupIds.isEmpty()) {
            return NONE;
        }
        Set<Long> ids = new HashSet<>(groupIds);
        ids.remove(null);
        return ids.isEmpty() ? NONE : new GroupScope(false, Set.copyOf(ids));
    }

    public boolean isAll() {
        return all;
    }

    public boolean isNone() {
        return !all && groupIds.isEmpty();
    }

    /** Group ids of a limited scope (empty for ALL and NONE). */
    public Set<Long> groupIds() {
        return groupIds;
    }

    /** Whether data belonging to this group is inside the scope. A null group is only inside ALL. */
    public boolean allows(Long groupId) {
        return all || (groupId != null && groupIds.contains(groupId));
    }

    /** Whether everything inside {@code other} is also inside this scope. */
    public boolean covers(GroupScope other) {
        if (all || other.isNone()) {
            return true;
        }
        if (other.all) {
            return false;
        }
        return groupIds.containsAll(other.groupIds);
    }

    public GroupScope union(GroupScope other) {
        if (all || other.all) {
            return ALL;
        }
        if (other.isNone()) {
            return this;
        }
        if (isNone()) {
            return other;
        }
        Set<Long> merged = new HashSet<>(groupIds);
        merged.addAll(other.groupIds);
        return new GroupScope(false, Set.copyOf(merged));
    }

    @Override
    public String toString() {
        return all ? "ALL" : (groupIds.isEmpty() ? "NONE" : groupIds.toString());
    }
}
