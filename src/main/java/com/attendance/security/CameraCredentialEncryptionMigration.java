package com.attendance.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * At start-up, encrypts camera passwords that are still stored as plain text
 * (only when an encryption key is configured).
 */
@Component
@Order(1)
@RequiredArgsConstructor
@Slf4j
public class CameraCredentialEncryptionMigration implements ApplicationRunner {

    private final JdbcTemplate jdbc;
    private final FieldEncryptor encryptor;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!encryptor.isEnabled()) {
            return;
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id, password from camera_configs where password is not null and password not like ?",
                FieldEncryptor.PREFIX + "%");
        for (Map<String, Object> row : rows) {
            Object id = row.get("id");
            String plain = (String) row.get("password");
            jdbc.update("update camera_configs set password = ? where id = ?", encryptor.encrypt(plain), id);
        }
        if (!rows.isEmpty()) {
            log.info("Encrypted {} stored camera password(s)", rows.size());
        }
    }
}
