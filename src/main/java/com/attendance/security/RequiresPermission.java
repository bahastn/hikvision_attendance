package com.attendance.security;

import java.lang.annotation.*;

/**
 * Declares the module/action a controller method needs. Repeat the annotation to
 * accept any one of several permissions (e.g. a lookup list used by several pages).
 * <p>
 * Every controller method must carry this, {@link AuthenticatedOnly} or
 * {@link PublicEndpoint}; requests to unannotated handlers are refused.
 * Group-level checks (which records) are done inside the method.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Repeatable(RequiresPermission.List.class)
public @interface RequiresPermission {

    AppModule module();

    PermissionAction action();

    @Target({ElementType.METHOD, ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    @interface List {
        RequiresPermission[] value();
    }
}
