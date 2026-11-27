package com.attendance.security;

import java.lang.annotation.*;

/** Handlers a user may still reach while they are forced to change their password. */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AllowDuringPasswordChange {
}
