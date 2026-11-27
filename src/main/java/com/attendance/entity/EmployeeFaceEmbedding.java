package com.attendance.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * <b>Legacy</b> (kept only so existing data can be migrated): replaced by {@link EmployeeFaceSample},
 * which allows several samples per employee. At startup the rows here are copied into ENROLLED
 * samples; nothing reads this table for matching any more.
 *
 * <p>An InsightFace embedding for one employee, used by the secondary (fallback) face-recognition
 * layer. Kept in a separate table (one row per employee) rather than as a column on
 * {@link Employee} so the constantly-loaded {@code Employee} entity stays lean — the 2 KB vector is
 * only read when the gallery is (re)built.
 *
 * <p>The embedding is stored as raw little-endian bytes (see
 * {@link com.attendance.service.insightface.EmbeddingCodec}): 512 floats = 2048 bytes. The stored
 * vector is L2-normalized, so matching is a plain dot product.
 */
@Entity
@Table(name = "employee_face_embeddings",
        uniqueConstraints = @UniqueConstraint(name = "uk_face_embedding_employee", columnNames = "employee_id"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmployeeFaceEmbedding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Owning employee's database id. Nullable at the column level on purpose: Hibernate
     * {@code ddl-auto=update} cannot add a NOT NULL column to an existing populated table, and the
     * one-per-employee rule is enforced in the service layer / by the unique constraint.
     */
    @Column(name = "employee_id")
    private Long employeeId;

    /**
     * L2-normalized embedding, little-endian 4-bytes-per-float. Mapped as a sized binary column
     * (not {@code @Lob}) so it becomes {@code bytea} on PostgreSQL and {@code VARBINARY} on the H2
     * test database — {@code @Lob}/BLOB is not recognized by H2 in PostgreSQL mode. 512 floats =
     * 2048 bytes; the length leaves room for larger models.
     */
    @Column(name = "embedding", length = 8192)
    private byte[] embedding;

    /** Number of floats in the embedding (e.g. 512), for sanity/version checks. */
    @Column(name = "dim")
    private Integer dim;

    /** InsightFace model that produced this embedding (e.g. buffalo_l). */
    @Column(name = "model")
    private String model;

    /** Detector confidence of the enrolled photo's face, for diagnostics. */
    @Column(name = "det_score")
    private Double detScore;

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
