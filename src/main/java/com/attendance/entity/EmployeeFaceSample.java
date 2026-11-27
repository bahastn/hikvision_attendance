package com.attendance.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * One reference face sample (photo + InsightFace embedding) of an employee. An employee can have
 * several samples — different angles, glasses, lighting, and real camera captures confirmed by a
 * reviewer — and recognition compares a captured face against all of them.
 *
 * <p>Exactly one sample per employee is normally the {@link Source#ENROLLED} "primary" sample: the
 * same photo that is pushed to the cameras' face libraries ({@code faces/enrolled/employee_{id}.jpg}).
 * It is managed by face enrollment and replaced when the enrolled photo changes. The other samples
 * live under {@code faces/samples/employee_{id}/}.
 *
 * <p>The embedding is stored as little-endian float bytes, L2-normalized (see
 * {@link com.attendance.service.insightface.EmbeddingCodec}).
 */
@Entity
@Table(name = "employee_face_samples",
        indexes = @Index(name = "idx_face_sample_employee", columnList = "employee_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeFaceSample {

    /** Where a sample came from. */
    public enum Source {
        /** The enrollment photo also pushed to the cameras (one per employee). */
        ENROLLED,
        /** An extra photo uploaded by an administrator. */
        UPLOADED,
        /** A camera capture a reviewer confirmed as this employee. */
        REVIEW_CONFIRMED,
        /** A camera capture added automatically after a very confident match. */
        AUTO_LEARNED;

        /** Samples created by the system from camera captures (rotated when the cap is reached). */
        public boolean isLearned() {
            return this == REVIEW_CONFIRMED || this == AUTO_LEARNED;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Owning employee's database id. */
    @Column(name = "employee_id", nullable = false)
    private Long employeeId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 24)
    private Source source;

    /** Public URL of the sample photo ({@code /faces/...}). */
    @Column(name = "image_url", length = 512)
    private String imageUrl;

    /** L2-normalized embedding, little-endian 4 bytes per float. */
    @Column(name = "embedding", length = 8192, nullable = false)
    private byte[] embedding;

    @Column(name = "dim")
    private Integer dim;

    /** InsightFace model that produced the embedding (e.g. buffalo_l). */
    @Column(name = "model", length = 64)
    private String model;

    @Column(name = "det_score")
    private Double detScore;

    /** 0..1 overall quality (see FaceQuality#score). */
    @Column(name = "quality_score")
    private Double qualityScore;

    /** Shorter side of the face box in pixels. */
    @Column(name = "face_size")
    private Double faceSize;

    @Column(name = "yaw")
    private Double yaw;

    @Column(name = "pitch")
    private Double pitch;

    @Column(name = "blur")
    private Double blur;

    /** For learned samples: the face capture it came from. */
    @Column(name = "source_capture_id")
    private Long sourceCaptureId;

    /** For learned samples: the match score that justified learning it. */
    @Column(name = "match_score")
    private Double matchScore;

    /** Login name of the user who uploaded/confirmed it, when known. */
    @Column(name = "created_by", length = 128)
    private String createdBy;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
