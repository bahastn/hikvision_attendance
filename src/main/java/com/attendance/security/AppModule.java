package com.attendance.security;

/**
 * The parts of the application a permission can be granted on.
 * <p>
 * Group-scoped modules hold data that belongs to employees, so a grant on them
 * can be limited to specific employee groups. The other modules are
 * organisation-wide: a grant on them always covers everything.
 */
public enum AppModule {

    EMPLOYEES(true),
    ATTENDANCE(true),
    LEAVES(true),
    GROUPS(true),
    SHIFT_SCHEDULE(true),

    ATTENDANCE_SYNC(false),
    SHIFTS(false),
    CAMERAS(false),
    FACE_RECOGNITION(false),
    SETTINGS(false),
    USER_MANAGEMENT(false);

    private final boolean groupScoped;

    AppModule(boolean groupScoped) {
        this.groupScoped = groupScoped;
    }

    public boolean isGroupScoped() {
        return groupScoped;
    }

    /** Key in messages*.properties holding the display name of this module. */
    public String messageKey() {
        return "module." + name();
    }
}
