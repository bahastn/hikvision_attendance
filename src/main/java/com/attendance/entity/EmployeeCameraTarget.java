package com.attendance.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Tracks which cameras an employee's face has been pushed to, along with the
 * camera-local person ID (PID) the camera assigned. PIDs are only valid on the
 * camera that issued them, so each (employee, camera) pair keeps its own.
 */
@Entity
@Table(name = "employee_camera_targets",
        uniqueConstraints = @UniqueConstraint(columnNames = {"employee_id", "camera_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeCameraTarget {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id", nullable = false)
    private Employee employee;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "camera_id", nullable = false)
    private CameraConfig camera;

    /** Person ID assigned by this camera's face library. */
    @Column(name = "pid")
    private String pid;

    @Column(name = "enrolled_at")
    private LocalDateTime enrolledAt;
}
