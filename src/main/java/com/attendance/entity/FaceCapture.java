package com.attendance.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One face picture captured by a camera. Every face event with a picture lands here;
 * rows (and their image files) are deleted once they are older than the configured
 * retention (24 hours by default). A capture that has been turned into an employee
 * is kept as long as any other capture — the enrolled photo is copied to the
 * employee's own persistent file at enrollment time.
 */
@Entity
@Table(name = "face_captures", indexes = {
        @Index(name = "idx_face_capture_expires", columnList = "expires_at"),
        @Index(name = "idx_face_capture_captured", columnList = "captured_at")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FaceCapture {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Camera that took the picture. */
    @Column(name = "camera_id")
    private Long cameraId;

    @Column(name = "camera_name")
    private String cameraName;

    /** Public URL path of the stored JPEG (served from the face storage dir). */
    @Column(name = "image_url")
    private String imageUrl;

    /** Employee matched by the camera (certificateNumber / PID), when known. */
    @Column(name = "matched_employee_id")
    private String matchedEmployeeId;

    @Column(name = "matched_employee_name")
    private String matchedEmployeeName;

    /** Person name reported by the camera event, if any. */
    @Column(name = "person_name")
    private String personName;

    private Double similarity;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    /** When this capture becomes eligible for deletion. */
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (capturedAt == null) {
            capturedAt = now;
        }
    }
}
