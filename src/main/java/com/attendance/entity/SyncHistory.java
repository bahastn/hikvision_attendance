package com.attendance.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Audit trail for all synchronization operations between the application and cameras.
 * Enables rollback, reporting, and troubleshooting.
 */
@Entity
@Table(name = "sync_history")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sync_time", nullable = false)
    private LocalDateTime syncTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "camera_id")
    private CameraConfig camera;

    @Column(name = "operation", nullable = false)
    @Enumerated(EnumType.STRING)
    private SyncOperation operation;

    @Column(name = "employee_id")
    private Long employeeId;

    @Column(name = "employee_number")
    private String employeeNumber;

    @Column(name = "camera_pid")
    private String cameraPid;

    @Column(name = "previous_value", length = 1000)
    private String previousValue;

    @Column(name = "new_value", length = 1000)
    private String newValue;

    @Column(name = "performed_by")
    private String performedBy;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private SyncResultStatus status;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "sync_batch_id")
    private String syncBatchId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (syncTime == null) {
            syncTime = LocalDateTime.now();
        }
    }

    public enum SyncOperation {
        IMPORT,         // Imported from camera to database
        EXPORT,         // Pushed from database to camera
        LINK,           // Linked camera PID to existing employee
        UNLINK,         // Removed link between camera and employee
        DELETE,         // Removed from camera
        UPDATE,         // Updated existing record
        ACTIVATE,       // Activated employee on camera
        DEACTIVATE      // Deactivated employee on camera
    }

    public enum SyncResultStatus {
        SUCCESS,
        FAILED,
        PARTIAL,
        SKIPPED
    }
}
