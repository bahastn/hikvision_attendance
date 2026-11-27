package com.attendance.entity;

import com.attendance.security.AppModule;
import com.attendance.security.PermissionAction;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * One permission row inside a role: a module, the actions allowed on it, and
 * (for group-scoped modules) the employee groups it applies to.
 */
@Entity
@Table(name = "app_role_grants")
@Getter
@Setter
@NoArgsConstructor
public class RoleGrant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false)
    private AppRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private AppModule module;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "app_role_grant_actions", joinColumns = @JoinColumn(name = "grant_id"))
    @Enumerated(EnumType.STRING)
    @Column(name = "action", nullable = false, length = 20)
    @BatchSize(size = 50)
    private Set<PermissionAction> actions = new HashSet<>();

    /** True = every employee group (and employees without a group). Always true for organisation-wide modules. */
    @Column(name = "all_groups", nullable = false)
    private boolean allGroups;

    /** Kept as a list so EmployeeGroup.hashCode is never needed. */
    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "app_role_grant_groups",
            joinColumns = @JoinColumn(name = "grant_id"),
            inverseJoinColumns = @JoinColumn(name = "group_id"))
    @BatchSize(size = 50)
    private List<EmployeeGroup> groups = new ArrayList<>();
}
