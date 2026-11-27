package com.attendance.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/** Who did what to accounts and permissions, and every sign-in attempt. */
@Entity
@Table(name = "security_audit_log", indexes = {
        @Index(name = "idx_security_audit_time", columnList = "event_time"),
        @Index(name = "idx_security_audit_actor", columnList = "actor")
})
@Getter
@Setter
@NoArgsConstructor
public class SecurityAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_time", nullable = false)
    private LocalDateTime eventTime;

    @Column(length = 60)
    private String actor;

    @Column(nullable = false, length = 40)
    private String action;

    @Column(length = 120)
    private String target;

    @Column(length = 2000)
    private String details;

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(nullable = false)
    private boolean success = true;
}
