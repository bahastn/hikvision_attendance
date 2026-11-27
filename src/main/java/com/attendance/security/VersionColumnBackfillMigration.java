package com.attendance.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backfills the optimistic-lock {@code version} column on versioned tables that
 * were created before the column existed.
 * <p>
 * When {@code @Version Long version} is added to an entity whose table already
 * holds rows, {@code ddl-auto=update} adds the column as NULL for those rows.
 * Hibernate then NPEs the first time such a row is updated (it computes
 * {@code null + 1} to bump the version), which rolls back the transaction. For
 * {@code app_users} that happens on the very next sign-in — success or failure —
 * because every sign-in updates the account row. This sets any NULL version to 0
 * so those rows can be updated normally. Idempotent and cheap.
 */
@Component
@Order(-1) // before SecurityBootstrap and the other migrations
@RequiredArgsConstructor
@Slf4j
public class VersionColumnBackfillMigration implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        backfill("app_users");
        backfill("app_roles");
    }

    private void backfill(String table) {
        // Table names are fixed internal constants, not user input.
        int updated = jdbc.update("update " + table + " set version = 0 where version is null");
        if (updated > 0) {
            log.info("Backfilled version=0 on {} row(s) in {}", updated, table);
        }
    }
}
