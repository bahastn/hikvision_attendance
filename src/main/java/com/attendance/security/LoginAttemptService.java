package com.attendance.security;

import com.attendance.entity.AppUser;
import com.attendance.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Counts failed sign-ins per account and locks the account for a while after
 * too many in a row. A successful sign-in resets the counter.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LoginAttemptService {

    private final AppUserRepository userRepository;
    private final SecurityAuditService audit;

    @Value("${app.security.login.max-failed-attempts:5}")
    private int maxFailedAttempts;

    @Value("${app.security.login.lock-minutes:15}")
    private int lockMinutes;

    @Transactional
    public void recordFailure(String username, String reason) {
        String name = username == null ? "" : username.trim();
        Optional<AppUser> found = userRepository.findByUsernameIgnoreCase(name);
        if (found.isEmpty()) {
            // Do not store the submitted value verbatim: it is frequently a password typed
            // into the username box, and the audit log is readable by every admin.
            audit.recordAs("unknown", "LOGIN_FAILED", maskUsername(name), "Unknown username", false);
            return;
        }
        AppUser user = found.get();
        if (user.isLockedNow()) {
            audit.recordAs(user.getUsername(), "LOGIN_FAILED", user.getUsername(), "Account is locked", false);
            return;
        }
        int attempts = user.getFailedLoginAttempts() + 1;
        user.setFailedLoginAttempts(attempts);
        audit.recordAs(user.getUsername(), "LOGIN_FAILED", user.getUsername(), reason, false);
        if (attempts >= maxFailedAttempts) {
            user.setLockedUntil(LocalDateTime.now().plusMinutes(lockMinutes));
            user.setFailedLoginAttempts(0);
            log.warn("Account '{}' locked for {} minutes after {} failed sign-ins", user.getUsername(), lockMinutes, attempts);
            audit.recordAs("system", "ACCOUNT_LOCKED", user.getUsername(),
                    "Locked for " + lockMinutes + " minutes after " + attempts + " failed sign-ins", true);
        }
        userRepository.save(user);
    }

    /** Keep only the first character so an operator can spot a typo, without logging a possible password. */
    private static String maskUsername(String value) {
        if (value == null || value.isEmpty()) {
            return "(empty)";
        }
        return value.charAt(0) + "***(" + value.length() + ")";
    }

    @Transactional
    public void recordSuccess(String username) {
        userRepository.findByUsernameIgnoreCase(username).ifPresent(user -> {
            user.setFailedLoginAttempts(0);
            user.setLockedUntil(null);
            user.setLastLoginAt(LocalDateTime.now());
            userRepository.save(user);
            audit.recordAs(user.getUsername(), "LOGIN_SUCCESS", user.getUsername(), null, true);
        });
    }
}
