package com.attendance.repository;

import com.attendance.entity.SecurityAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SecurityAuditLogRepository extends JpaRepository<SecurityAuditLog, Long> {

    List<SecurityAuditLog> findAllByOrderByEventTimeDesc(Pageable pageable);

    @Modifying
    @Query("delete from SecurityAuditLog a where a.eventTime < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);
}
