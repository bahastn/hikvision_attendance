package com.attendance.repository;

import com.attendance.entity.FaceCapture;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface FaceCaptureRepository extends JpaRepository<FaceCapture, Long> {

    /** Captures still within their retention window, newest first. */
    List<FaceCapture> findByExpiresAtAfterOrderByCapturedAtDesc(LocalDateTime now);

    /** Captures still within their retention window for one camera, newest first. */
    List<FaceCapture> findByCameraIdAndExpiresAtAfterOrderByCapturedAtDesc(Long cameraId, LocalDateTime now);

    /** Captures whose retention window has ended — candidates for deletion. */
    List<FaceCapture> findByExpiresAtBefore(LocalDateTime now);
}
