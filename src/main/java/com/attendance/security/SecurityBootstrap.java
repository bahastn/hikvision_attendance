package com.attendance.security;

import com.attendance.entity.AppUser;
import com.attendance.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;

/**
 * Creates the first super-admin account when no account exists yet.
 * <p>
 * The password comes from {@code app.security.bootstrap-admin.password}
 * (env BOOTSTRAP_ADMIN_PASSWORD). When that is empty a random password is
 * generated and printed once in the log. Either way the admin must choose a
 * new password at first sign-in.
 */
@Component
@Order(0)
@RequiredArgsConstructor
@Slf4j
public class SecurityBootstrap implements ApplicationRunner {

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityAuditService audit;

    @Value("${app.security.bootstrap-admin.username:admin}")
    private String username;

    @Value("${app.security.bootstrap-admin.password:}")
    private String configuredPassword;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.count() > 0) {
            return;
        }
        boolean generated = configuredPassword == null || configuredPassword.isBlank();
        String password = generated ? randomPassword(20) : configuredPassword;

        AppUser admin = new AppUser();
        admin.setUsername(username);
        admin.setFullName("Administrator");
        admin.setPasswordHash(passwordEncoder.encode(password));
        admin.setEnabled(true);
        admin.setSuperAdmin(true);
        admin.setMustChangePassword(true);
        admin.setCreatedBy("system");
        userRepository.save(admin);
        audit.recordAs("system", "USER_CREATED", username, "Initial super admin created at first start", true);

        if (generated) {
            log.warn("""

                    ================================================================
                     First start: created super admin '{}'
                     One-time password: {}
                     Sign in and choose a new password. This is shown only once.
                    ================================================================""", username, password);
        } else {
            log.warn("First start: created super admin '{}' with the configured bootstrap password. "
                    + "You will be asked to change it at first sign-in.", username);
        }
    }

    private static String randomPassword(int length) {
        SecureRandom random = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        // guarantee at least one digit so it also satisfies the password policy
        sb.setCharAt(random.nextInt(length), (char) ('2' + random.nextInt(8)));
        return sb.toString();
    }
}
