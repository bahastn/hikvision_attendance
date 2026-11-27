package com.attendance.repository;

import com.attendance.entity.FaceCapture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FaceCaptureRepository extends JpaRepository<FaceCapture, Long> {

    /** Captures still within their retention window, newest first. */
    List<FaceCapture> findByExpiresAtAfterOrderByCapturedAtDesc(LocalDateTime now);

    /** Captures still within their retention window for one camera, newest first. */
    List<FaceCapture> findByCameraIdAndExpiresAtAfterOrderByCapturedAtDesc(Long cameraId, LocalDateTime now);

    /**
     * Expired captures eligible for deletion, EXCLUDING those pending human review (so an uncertain
     * InsightFace suggestion is never purged before someone decides on it). Rows with a null review
     * status (ordinary captures) are included.
     */
    @Query("SELECT f FROM FaceCapture f WHERE f.expiresAt < :now "
            + "AND (f.reviewStatus IS NULL OR f.reviewStatus <> :excluded)")
    List<FaceCapture> findExpiredForCleanup(@Param("now") LocalDateTime now,
                                            @Param("excluded") FaceCapture.ReviewStatus excluded);

    /** Captures in a given review state (e.g. PENDING_REVIEW), newest first. */
    List<FaceCapture> findByReviewStatusOrderByCapturedAtDesc(FaceCapture.ReviewStatus reviewStatus);
}
