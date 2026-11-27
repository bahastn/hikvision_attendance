package com.attendance.security;

import com.attendance.entity.SecurityAuditLog;
import com.attendance.repository.SecurityAuditLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Writes the security audit trail: sign-ins, lockouts, and every change to users,
 * roles and passwords. Entries are written in their own transaction so they survive
 * even when the surrounding operation fails.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecurityAuditService {

    private final SecurityAuditLogRepository repository;

    @Value("${app.security.audit-retention-days:365}")
    private int retentionDays;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(String action, String target, String details, boolean success) {
        recordAs(currentActor(), action, target, details, success);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordAs(String actor, String action, String target, String details, boolean success) {
        try {
            SecurityAuditLog entry = new SecurityAuditLog();
            entry.setEventTime(LocalDateTime.now());
            entry.setActor(truncate(actor, 60));
            entry.setAction(truncate(action, 40));
            entry.setTarget(truncate(target, 120));
            entry.setDetails(truncate(details, 2000));
            entry.setIpAddress(truncate(currentIp(), 64));
            entry.setSuccess(success);
            repository.save(entry);
        } catch (RuntimeException e) {
            // Never let auditing break the operation itself, but make the failure visible.
            log.error("Could not write security audit entry {} for {}: {}", action, target, e.getMessage());
        }
    }

    @Transactional(readOnly = true)
    public List<SecurityAuditLog> latest(int limit) {
        return repository.findAllByOrderByEventTimeDesc(PageRequest.of(0, Math.max(1, Math.min(limit, 1000))));
    }

    @Scheduled(cron = "${app.security.audit-cleanup-cron:0 30 3 * * ?}")
    @Transactional
    public void purgeOldEntries() {
        if (retentionDays <= 0) {
            return;
        }
        int removed = repository.deleteOlderThan(LocalDateTime.now().minusDays(retentionDays));
        if (removed > 0) {
            log.info("Removed {} security audit entries older than {} days", removed, retentionDays);
        }
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
            return "system";
        }
        return auth.getName();
    }

    /** Remote address of the current request. X-Forwarded-For is ignored on purpose (it is client-controlled). */
    private static String currentIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            return request.getRemoteAddr();
        }
        return null;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
