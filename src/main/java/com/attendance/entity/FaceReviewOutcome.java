package com.attendance.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Permanent, image-free log of human review decisions on suggested matches, used to calibrate the
 * InsightFace thresholds against real-world results (the captures themselves are purged after the
 * retention window). One row per decision; no picture and no embedding is kept.
 */
@Entity
@Table(name = "face_review_outcomes",
        indexes = @Index(name = "idx_face_review_outcome_time", columnList = "decided_at"))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FaceReviewOutcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "capture_id")
    private Long captureId;

    /** INSIGHTFACE (uncertain suggestion) or CAMERA_VERIFY (camera match InsightFace could not confirm). */
    @Column(name = "source", length = 32)
    private String source;

    /** The suggested employee's business id. */
    @Column(name = "candidate_employee_id")
    private String candidateEmployeeId;

    /** InsightFace similarity of the suggestion (0..1). */
    @Column(name = "candidate_score")
    private Double candidateScore;

    /** True when the reviewer confirmed the suggested employee; false for a rejection or a different person. */
    @Column(name = "confirmed_candidate")
    private Boolean confirmedCandidate;

    @Column(name = "decided_at")
    private LocalDateTime decidedAt;

    @PrePersist
    protected void onCreate() {
        if (decidedAt == null) {
            decidedAt = LocalDateTime.now();
        }
    }
}
