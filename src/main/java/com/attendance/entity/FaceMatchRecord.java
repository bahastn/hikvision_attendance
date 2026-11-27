package com.attendance.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Records face match events from cameras for review and processing.
 * Allows users to download, review, and sync matched persons to the database.
 */
@Entity
@Table(name = "face_match_records",
        uniqueConstraints = @UniqueConstraint(name = "uk_face_match_key", columnNames = "match_key"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FaceMatchRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Natural, deduplicating key for one raw match: {@code cameraId|certificateNumber-or-pid|snapTimeMillis}.
     * Unique, so re-polling overlapping windows (or a restart) can never store the same match twice.
     */
    @Column(name = "match_key", length = 255)
    private String matchKey;

    @Column(name = "camera_pid")
    private String cameraPid;

    @Column(name = "certificate_number")
    private String certificateNumber;

    @Column(name = "person_name")
    private String personName;

    @Column(name = "similarity")
    private Double similarity;

    @Column(name = "match_time")
    private LocalDateTime matchTime;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "camera_id")
    private CameraConfig camera;

    @Column(name = "face_pic_url")
    private String facePicUrl;

    @Column(name = "snap_pic_url")
    private String snapPicUrl;

    @Column(name = "status")
    @Enumerated(EnumType.STRING)
    private MatchStatus status = MatchStatus.NEW;

    @Column(name = "processed_by")
    private String processedBy;

    @Column(name = "processed_at")
    private LocalDateTime processedAt;

    @Column(name = "employee_id")
    private Long employeeId;

    // --- Central-server sync (outbox) -------------------------------------------------
    // These track the lightweight push of this match (employeeId, date, time, cameraId — no
    // image) to the central server, independently of the review MatchStatus above.

    @Column(name = "central_sync_state")
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private CentralSyncState centralSyncState = CentralSyncState.PENDING;

    @Column(name = "sync_attempts")
    @Builder.Default
    private Integer syncAttempts = 0;

    @Column(name = "last_sync_attempt")
    private LocalDateTime lastSyncAttempt;

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;

    @Column(name = "sync_error")
    private String syncError;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }

    public enum MatchStatus {
        NEW,        // Just recorded, not yet reviewed
        REVIEWED,   // User has seen it
        PROCESSED,  // Converted to employee or linked
        IGNORED,    // User chose to ignore
        EXPORTED    // Included in an export/download
    }

    /** Lifecycle of pushing this match to the central server. */
    public enum CentralSyncState {
        PENDING,    // Recorded locally, not yet pushed
        SENT,       // Accepted by the central server
        FAILED      // Last push failed; will be retried
    }
}
