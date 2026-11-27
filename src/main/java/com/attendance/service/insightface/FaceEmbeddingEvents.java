package com.attendance.service.insightface;

/**
 * Application events published by {@code FaceEnrollmentService} so the InsightFace embedding is
 * (re)generated or removed after the enrollment transaction commits, off the enrollment response
 * path. Handled by {@link InsightFaceEnrollmentListener}.
 */
public final class FaceEmbeddingEvents {

    private FaceEmbeddingEvents() {
    }

    /** An employee's face photo was enrolled/updated; (re)build its embedding. */
    public record FaceEnrolled(Long employeeDbId) {
    }

    /** An employee's face was removed; delete its embedding. */
    public record FaceRemoved(Long employeeDbId) {
    }

    /**
     * A reviewer confirmed a pending capture as this employee; the capture may become a new
     * reference sample (see {@code FaceLearningService}).
     */
    public record CaptureConfirmed(Long captureId, Long employeeDbId, String imageUrl, String username) {
    }
}
