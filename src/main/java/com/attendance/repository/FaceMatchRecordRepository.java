package com.attendance.repository;

import com.attendance.entity.FaceMatchRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface FaceMatchRecordRepository extends JpaRepository<FaceMatchRecord, Long> {

    List<FaceMatchRecord> findByStatusOrderByMatchTimeDesc(FaceMatchRecord.MatchStatus status);

    /** Idempotency lookup for raw-match ingestion. */
    boolean existsByMatchKey(String matchKey);

    Optional<FaceMatchRecord> findByMatchKey(String matchKey);

    /**
     * Outbox query: the oldest matches still waiting to be pushed to the central server.
     * Fetches the camera eagerly so the sender can read camera id/name/externalId without an
     * open session.
     */
    @Query("SELECT f FROM FaceMatchRecord f LEFT JOIN FETCH f.camera "
            + "WHERE f.centralSyncState IN :states AND f.syncAttempts < :maxAttempts "
            + "ORDER BY f.matchTime ASC")
    List<FaceMatchRecord> findPendingForSync(
            @Param("states") Collection<FaceMatchRecord.CentralSyncState> states,
            @Param("maxAttempts") int maxAttempts,
            Pageable pageable);

    long countByCentralSyncState(FaceMatchRecord.CentralSyncState state);

    List<FaceMatchRecord> findByCameraIdAndMatchTimeBetweenOrderByMatchTimeDesc(
            Long cameraId, LocalDateTime start, LocalDateTime end);

    List<FaceMatchRecord> findByMatchTimeBetweenOrderByMatchTimeDesc(
            LocalDateTime start, LocalDateTime end);

    @Query("SELECT f FROM FaceMatchRecord f WHERE f.status = 'NEW' AND f.similarity >= :threshold ORDER BY f.matchTime DESC")
    List<FaceMatchRecord> findHighConfidenceNewMatches(@Param("threshold") Double threshold);

    /**
     * Paginated, filtered search for the Face Matches page. All filters are optional
     * (pass {@code null} to ignore). {@code q} is matched, already lower-cased and wrapped in
     * {@code %...%}, against the camera-reported person name and the certificate/ID number.
     * Ordered newest first.
     */
    @Query("SELECT f FROM FaceMatchRecord f WHERE "
            + "(:status IS NULL OR f.status = :status) AND "
            + "(:cameraId IS NULL OR f.camera.id = :cameraId) AND "
            + "(:minSimilarity IS NULL OR f.similarity >= :minSimilarity) AND "
            + "(:from IS NULL OR f.matchTime >= :from) AND "
            + "(:to IS NULL OR f.matchTime <= :to) AND "
            + "(:q IS NULL OR LOWER(COALESCE(f.personName, '')) LIKE :q "
            + "          OR LOWER(COALESCE(f.certificateNumber, '')) LIKE :q) "
            + "ORDER BY f.matchTime DESC")
    Page<FaceMatchRecord> search(@Param("status") FaceMatchRecord.MatchStatus status,
                                 @Param("cameraId") Long cameraId,
                                 @Param("minSimilarity") Double minSimilarity,
                                 @Param("from") LocalDateTime from,
                                 @Param("to") LocalDateTime to,
                                 @Param("q") String q,
                                 Pageable pageable);

    long countByStatus(FaceMatchRecord.MatchStatus status);

    @Query("SELECT COUNT(f) FROM FaceMatchRecord f WHERE f.camera.id = :cameraId AND f.matchTime >= :since")
    long countByCameraIdAndMatchTimeAfter(@Param("cameraId") Long cameraId, @Param("since") LocalDateTime since);
}
