package com.attendance.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * Password rules for sign-in accounts. Returns a message key describing the
 * first rule that fails, or null when the password is acceptable.
 */
@Component
public class PasswordPolicy {

    /** BCrypt only uses the first 72 bytes; longer passwords would be silently truncated. */
    private static final int MAX_LENGTH = 72;

    private static final Set<String> COMMON = Set.of(
            "password", "password1", "password123", "admin123", "administrator", "123456789",
            "1234567890", "qwertyuiop", "letmein123", "welcome123", "attendance", "hikvision",
            "parliament", "changeme123");

    private final int minLength;

    public PasswordPolicy(@Value("${app.security.password.min-length:10}") int minLength) {
        this.minLength = Math.max(8, minLength);
    }

    public int minLength() {
        return minLength;
    }

    public String violation(String password, String username) {
        if (password == null || password.length() < minLength) {
            return "password.policy.tooShort";
        }
        if (password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_LENGTH) {
            return "password.policy.tooLong";
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            return "password.policy.lettersAndDigits";
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (username != null && !username.isBlank() && lower.contains(username.toLowerCase(Locale.ROOT))) {
            return "password.policy.containsUsername";
        }
        if (COMMON.contains(lower)) {
            return "password.policy.common";
        }
        return null;
    }
}
