package com.attendance.security;

import java.lang.annotation.*;

/** Any signed-in user may call this handler (no module permission needed). */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuthenticatedOnly {
}
