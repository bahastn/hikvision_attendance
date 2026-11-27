package com.attendance.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "employee_groups")
@Getter
@Setter
@ToString(exclude = {"employees", "shift", "shiftRule"})
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(length = 500)
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_id")
    private Shift shift;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "shift_rule_id")
    private ShiftRule shiftRule;

    @Column(name = "is_active")
    @Builder.Default
    private Boolean isActive = true;

    // No cascade: deleting a group must never delete its employees.
    @OneToMany(mappedBy = "group")
    @Builder.Default
    private List<Employee> employees = new ArrayList<>();

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
}
