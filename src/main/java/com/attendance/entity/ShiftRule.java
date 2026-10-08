package com.attendance.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * ShiftRule defines attendance rules applied to a group of employees.
 * It controls how late check-in, early leave, and absent are determined.
 */
@Entity
@Table(name = "shift_rules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ShiftRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(length = 500)
    private String description;

    /** Minutes after shift start before check-in is considered late. Default 15. */
    @Column(name = "late_threshold_minutes")
    @Builder.Default
    private Integer lateThresholdMinutes = 15;

    /** Minutes before shift end that check-out is considered early leave. Default 15. */
    @Column(name = "early_leave_threshold_minutes")
    @Builder.Default
    private Integer earlyLeaveThresholdMinutes = 15;

    /** Minutes after shift start after which an employee is marked absent if not checked in. Default 240 (4 hours). */
    @Column(name = "absent_after_minutes")
    @Builder.Default
    private Integer absentAfterMinutes = 240;

    /** Whether a check-in is strictly required (if false, check-in is optional). Default true. */
    @Column(name = "require_check_in")
    @Builder.Default
    private Boolean requireCheckIn = true;

    /** Whether check-out is strictly required. Default true. */
    @Column(name = "require_check_out")
    @Builder.Default
    private Boolean requireCheckOut = true;

    /** Grace period in minutes for the entire shift (applied to both late and early leave). Default 0. */
    @Column(name = "grace_period_minutes")
    @Builder.Default
    private Integer gracePeriodMinutes = 0;

    /** Maximum allowed work hours per shift (overtime threshold). Null = no limit. */
    @Column(name = "max_work_hours")
    private Double maxWorkHours;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    @OneToMany(mappedBy = "shiftRule")
    @Builder.Default
    private List<EmployeeGroup> groups = new ArrayList<>();

    @OneToMany(mappedBy = "rule")
    @Builder.Default
    private List<ShiftSchedule> schedules = new ArrayList<>();

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Get the effective late threshold including grace period.
     */
    public int getEffectiveLateThreshold() {
        int late = lateThresholdMinutes != null ? lateThresholdMinutes : 15;
        int grace = gracePeriodMinutes != null ? gracePeriodMinutes : 0;
        return late + grace;
    }

    /**
     * Get the effective early leave threshold including grace period.
     */
    public int getEffectiveEarlyLeaveThreshold() {
        int early = earlyLeaveThresholdMinutes != null ? earlyLeaveThresholdMinutes : 15;
        int grace = gracePeriodMinutes != null ? gracePeriodMinutes : 0;
        return early + grace;
    }
}
