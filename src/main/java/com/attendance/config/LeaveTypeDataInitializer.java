package com.attendance.config;

import com.attendance.entity.LeaveType;
import com.attendance.repository.LeaveTypeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Seeds the default leave types and performs the one-time migration from the old
 * {@code leaves.leave_type} enum column (VARCHAR) to the {@code leave_type_id}
 * foreign key introduced by {@link com.attendance.entity.LeaveType}.
 * <p>
 * Runs after Hibernate's schema update (which creates {@code leave_types} and adds
 * the nullable {@code leave_type_id} column). The migration is idempotent: once the
 * legacy column is gone it is skipped, so normal startups do nothing extra.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LeaveTypeDataInitializer implements ApplicationRunner {

    private final LeaveTypeRepository leaveTypeRepository;
    private final JdbcTemplate jdbc;

    // Readable labels for legacy enum codes so historical leaves keep a meaningful name.
    private static final Map<String, String[]> LEGACY_LABELS = Map.of(
            "ANNUAL",    new String[]{"Annual Leave",    "مۆڵەتی ساڵانە"},
            "CASUAL",    new String[]{"Casual Leave",    "مۆڵەتی ئاسایی"},
            "MATERNITY", new String[]{"Maternity Leave", "مۆڵەتی دووگیانی"},
            "PATERNITY", new String[]{"Paternity Leave", "مۆڵەتی باوکایەتی"},
            "UNPAID",    new String[]{"Unpaid Leave",    "مۆڵەتی بێ موچە"},
            "OTHER",     new String[]{"Other",           "ئەوی تر"}
    );

    @Override
    public void run(ApplicationArguments args) {
        // Step 1, first and on its own: if the legacy NOT NULL column is still present,
        // relax it immediately. New leave inserts no longer set leave_type, so until this
        // happens they fail with a not-null violation. Doing it up front means a later
        // failure (seeding, backfill) can never leave leave creation broken.
        try {
            if (legacyColumnExists()) {
                relaxLegacyColumn();
            }
        } catch (Exception e) {
            log.error("Could not relax legacy leave_type column: {}", e.getMessage(), e);
        }
        try {
            seedPrimaryTypes();
        } catch (Exception e) {
            log.error("Seeding leave types failed: {}", e.getMessage(), e);
        }
        try {
            migrateLegacyLeaveTypeColumn();
        } catch (Exception e) {
            log.error("Legacy leave type migration failed: {}", e.getMessage(), e);
        }
    }

    private void seedPrimaryTypes() {
        ensureType("NORMAL", "Normal Leave", "مۆڵەتی ئاسایی", 1, true);
        ensureType("SICK",   "Sick Leave",   "مۆڵەتی نەخۆشی", 2, true);
        ensureType("HOURLY", "Hourly Leave", "کاتی",          3, true);
    }

    private LeaveType ensureType(String code, String nameEn, String nameKu, int order, boolean active) {
        return leaveTypeRepository.findByCodeIgnoreCase(code).orElseGet(() ->
                leaveTypeRepository.save(LeaveType.builder()
                        .code(code)
                        .nameEn(nameEn)
                        .nameKu(nameKu)
                        .displayOrder(order)
                        .isActive(active)
                        .build()));
    }

    private void migrateLegacyLeaveTypeColumn() {
        if (!legacyColumnExists()) return;
        log.info("Migrating legacy leaves.leave_type values to leave_type_id ...");

        List<String> codes;
        try {
            codes = jdbc.queryForList(
                    "SELECT DISTINCT leave_type FROM leaves WHERE leave_type IS NOT NULL", String.class);
        } catch (Exception e) {
            log.warn("Could not read legacy leave_type values, keeping column: {}", e.getMessage());
            return;
        }

        int order = 10;
        for (String raw : codes) {
            if (raw == null || raw.isBlank()) continue;
            String code = raw.trim().toUpperCase();
            LeaveType type = leaveTypeRepository.findByCodeIgnoreCase(code).orElse(null);
            if (type == null) {
                // Create legacy types INACTIVE: they keep historical leaves readable but stay
                // out of the "request leave" dropdown, which shows only active types. Admins can
                // reactivate or permanently delete them from the Leave Types page.
                String[] labels = LEGACY_LABELS.getOrDefault(code, new String[]{humanize(code), null});
                type = ensureType(code, labels[0], labels[1], order++, false);
            }
            try {
                int updated = jdbc.update(
                        "UPDATE leaves SET leave_type_id = ? WHERE leave_type_id IS NULL AND UPPER(leave_type) = ?",
                        type.getId(), code);
                if (updated > 0) log.info("  leave_type '{}' -> type #{} ({} rows)", code, type.getId(), updated);
            } catch (Exception e) {
                log.warn("Failed migrating leave_type '{}': {}", code, e.getMessage());
            }
        }

        Long remaining = jdbc.queryForObject(
                "SELECT COUNT(*) FROM leaves WHERE leave_type_id IS NULL AND leave_type IS NOT NULL", Long.class);
        if (remaining != null && remaining == 0) {
            try {
                jdbc.execute("ALTER TABLE leaves DROP COLUMN leave_type");
                log.info("Legacy leaves.leave_type column migrated and dropped.");
            } catch (Exception e) {
                log.warn("Could not drop legacy leave_type column ({}); relaxing NOT NULL instead.", e.getMessage());
                relaxLegacyColumn();
            }
        } else {
            log.warn("Keeping legacy leave_type column: {} row(s) could not be migrated.", remaining);
            relaxLegacyColumn();
        }
    }

    /** Make the old column nullable so new inserts (which no longer set it) succeed. */
    private void relaxLegacyColumn() {
        try {
            jdbc.execute("ALTER TABLE leaves ALTER COLUMN leave_type DROP NOT NULL");
        } catch (Exception ignored) {
            // Column may already be nullable or the DB may use different syntax; not fatal.
        }
    }

    private boolean legacyColumnExists() {
        try {
            Integer c = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.columns " +
                            "WHERE UPPER(table_name) = 'LEAVES' AND UPPER(column_name) = 'LEAVE_TYPE'",
                    Integer.class);
            return c != null && c > 0;
        } catch (Exception e) {
            log.warn("Could not check for legacy leave_type column, skipping migration: {}", e.getMessage());
            return false;
        }
    }

    private static String humanize(String code) {
        String s = code.toLowerCase().replace('_', ' ').trim();
        return s.isEmpty() ? code : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
