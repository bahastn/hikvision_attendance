package com.attendance.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "camera_configs")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CameraConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "camera_name", nullable = false)
    private String cameraName;

    @Column(name = "ip_address", nullable = false)
    private String ipAddress;

    @Column(name = "port")
    private Integer port = 80;

    @Column(name = "username")
    private String username;

    /** Encrypted at rest when APP_ENCRYPTION_KEY is set; never printed by toString(). */
    @Column(name = "password")
    @Convert(converter = com.attendance.security.EncryptedStringConverter.class)
    @ToString.Exclude
    private String password;

    @Column(name = "protocol")
    private String protocol = "http";

    @Column(name = "sdk_port")
    private Integer sdkPort = 8000;

    @Column(name = "websocket_port")
    private Integer websocketPort = 7681;

    @Column(name = "rtsp_port")
    private Integer rtspPort = 554;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "is_connected")
    private Boolean isConnected = false;

    @Column(name = "location")
    private String location;

    @Column(name = "last_sync")
    private LocalDateTime lastSync;

    /**
     * When this camera's face library was last synced with the app. Separate from
     * {@link #lastSync}, which is refreshed on every event (connection health), so the periodic
     * library sync can tell when it last really ran.
     */
    @Column(name = "last_library_sync")
    private LocalDateTime lastLibrarySync;

    /**
     * High-water mark for raw face-match ingestion: the snapTime of the most recent match
     * already recorded from this camera's FCSearch history. Persisted so a restart or downtime
     * resumes from where it left off instead of replaying from "now" and losing the gap.
     */
    @Column(name = "last_match_time")
    private LocalDateTime lastMatchTime;

    /**
     * Stable identifier for this camera used when pushing matches to the central server.
     * Optional: set it to the device serial (or any site-wide unique id) so the central
     * server keeps a stable cameraId even if this camera is deleted and re-added (which would
     * change the generated {@link #id}). Falls back to {@link #id} when null.
     */
    @Column(name = "external_id")
    private String externalId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
