package com.attendance.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM encryption for secrets stored in the database (camera passwords).
 * <p>
 * The key comes from {@code app.security.encryption-key} (env APP_ENCRYPTION_KEY),
 * a Base64-encoded 32-byte value. Without a key, values are stored as plain text
 * and a warning is logged at start-up. Encrypted values start with {@value #PREFIX}
 * so existing plain-text rows keep working and are upgraded when re-saved.
 */
@Component
@Slf4j
public class FieldEncryptor {

    public static final String PREFIX = "enc:v1:";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static volatile FieldEncryptor instance;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public FieldEncryptor(@Value("${app.security.encryption-key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            this.key = null;
            log.warn("app.security.encryption-key (APP_ENCRYPTION_KEY) is not set: camera passwords are stored "
                    + "in the database as plain text. Set a Base64 32-byte key to encrypt them.");
        } else {
            byte[] raw = Base64.getDecoder().decode(base64Key.trim());
            if (raw.length != 32) {
                throw new IllegalStateException("app.security.encryption-key must be 32 bytes (Base64), got " + raw.length);
            }
            this.key = new SecretKeySpec(raw, "AES");
        }
        instance = this;
    }

    /** Used by the JPA converter, which Hibernate may instantiate outside Spring. */
    static FieldEncryptor get() {
        FieldEncryptor current = instance;
        if (current == null) {
            throw new IllegalStateException("FieldEncryptor is not initialised yet");
        }
        return current;
    }

    public boolean isEnabled() {
        return key != null;
    }

    public String encrypt(String plain) {
        // Always encrypt a real value: entity attributes are always held in plaintext,
        // so a user password that happens to start with the prefix must still be encrypted
        // (otherwise it would be stored as-is and then fail to decrypt on read).
        if (plain == null || key == null) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            ByteBuffer out = ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted);
            return PREFIX + Base64.getEncoder().encodeToString(out.array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt value", e);
        }
    }

    public String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            return stored; // plain text from before encryption was enabled
        }
        if (key == null) {
            throw new IllegalStateException("A camera password in the database is encrypted, but "
                    + "app.security.encryption-key (APP_ENCRYPTION_KEY) is not set.");
        }
        try {
            byte[] all = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Could not decrypt a stored camera password: wrong APP_ENCRYPTION_KEY?", e);
        }
    }
}
