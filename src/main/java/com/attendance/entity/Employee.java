package com.attendance.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.ColumnDefault;
import java.time.LocalDateTime;

@Entity
@Table(name = "employees")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Employee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String employeeId;

    @Column(nullable = false)
    private String fullName;

    @Column(name = "full_name_kurdish")
    private String fullNameKurdish;

    private String department;

    private String position;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @ColumnDefault("'EMPLOYEE'")
    @Builder.Default
    private EmployeeCategory category = EmployeeCategory.EMPLOYEE;

    private String email;

    private String phone;

    @Column(name = "face_template_id")
    private String faceTemplateId;

    @Column(name = "face_image_url")
    private String faceImageUrl;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "sync_status")
    @Enumerated(EnumType.STRING)
    private SyncStatus syncStatus = SyncStatus.PENDING;

    @Column(name = "last_camera_sync")
    private LocalDateTime lastCameraSync;

    @Column(name = "camera_sync_count")
    private Integer cameraSyncCount = 0;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private EmployeeGroup group;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (category == null) {
            category = EmployeeCategory.EMPLOYEE;
        }
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
