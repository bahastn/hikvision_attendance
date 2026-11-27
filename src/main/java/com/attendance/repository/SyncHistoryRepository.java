package com.attendance.repository;

import com.attendance.entity.SyncHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SyncHistoryRepository extends JpaRepository<SyncHistory, Long> {

    List<SyncHistory> findBySyncBatchIdOrderBySyncTimeDesc(String syncBatchId);

    List<SyncHistory> findByCameraIdAndSyncTimeBetweenOrderBySyncTimeDesc(
            Long cameraId, LocalDateTime start, LocalDateTime end);

    List<SyncHistory> findByEmployeeIdOrderBySyncTimeDesc(Long employeeId);

    @Query("SELECT s FROM SyncHistory s WHERE s.syncTime >= :since ORDER BY s.syncTime DESC")
    List<SyncHistory> findRecentSyncs(@Param("since") LocalDateTime since);

    @Query("SELECT s.operation, COUNT(s) FROM SyncHistory s WHERE s.syncTime >= :since GROUP BY s.operation")
    List<Object[]> countOperationsSince(@Param("since") LocalDateTime since);

    @Query("SELECT DISTINCT s.syncBatchId FROM SyncHistory s WHERE s.syncTime >= :since ORDER BY s.syncBatchId DESC")
    List<String> findRecentBatchIds(@Param("since") LocalDateTime since);
}
