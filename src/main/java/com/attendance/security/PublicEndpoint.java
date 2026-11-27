package com.attendance.security;

import java.lang.annotation.*;

/**
 * Reachable without signing in. The URL must also be permitted in SecurityConfig;
 * this annotation only tells the permission interceptor not to demand a permission.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface PublicEndpoint {
}
