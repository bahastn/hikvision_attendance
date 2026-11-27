package com.attendance.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.LockedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/** Form-login success/failure handling: lockout counting, audit, and session sign-in time. */
@Component
@RequiredArgsConstructor
public class LoginHandlers {

    private final LoginAttemptService loginAttempts;
    private final PermissionResolver permissionResolver;

    public AuthenticationSuccessHandler successHandler() {
        return (HttpServletRequest request, HttpServletResponse response, Authentication authentication) -> {
            loginAttempts.recordSuccess(authentication.getName());
            permissionResolver.invalidateAll();
            request.getSession().setAttribute(PermissionInterceptor.SESSION_AUTH_TIME, LocalDateTime.now());
            response.sendRedirect(request.getContextPath() + "/");
        };
    }

    public AuthenticationFailureHandler failureHandler() {
        return (HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) -> {
            String username = request.getParameter("username");
            loginAttempts.recordFailure(username, reasonOf(exception));
            // One generic message for every failure, so the page never reveals whether an account exists.
            response.sendRedirect(request.getContextPath() + "/login?error");
        };
    }

    private static String reasonOf(AuthenticationException e) {
        if (e instanceof LockedException) return "Account is locked";
        if (e instanceof DisabledException) return "Account is disabled";
        if (e instanceof BadCredentialsException) return "Wrong password";
        return e.getClass().getSimpleName();
    }
}
