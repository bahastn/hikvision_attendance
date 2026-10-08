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

    @Column(name = "password")
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

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
