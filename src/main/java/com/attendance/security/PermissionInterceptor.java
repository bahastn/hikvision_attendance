package com.attendance.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Enforces {@link RequiresPermission} / {@link AuthenticatedOnly} / {@link PublicEndpoint}
 * on every controller method of the application. Handlers without any of them are
 * refused, so a newly added endpoint is closed until someone decides who may use it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PermissionInterceptor implements HandlerInterceptor {

    /** Session attribute holding when the user signed in (set by the login success handler). */
    public static final String SESSION_AUTH_TIME = "attendance.authTime";

    private final AccessControlService access;
    private final ObjectMapper objectMapper;
    private final MessageSource messages;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true; // static resources: authentication is enforced by the security filter chain
        }
        if (!method.getBeanType().getPackageName().startsWith("com.attendance")) {
            return true; // framework handlers such as /error
        }
        if (has(method, PublicEndpoint.class)) {
            return true;
        }

        Set<RequiresPermission> required = requirements(method);
        boolean authenticatedOnly = has(method, AuthenticatedOnly.class);
        if (required.isEmpty() && !authenticatedOnly) {
            log.error("Refusing {} {}: handler {} has no permission annotation",
                    request.getMethod(), request.getRequestURI(), method);
            throw new AccessDeniedException(msg("access.denied.unconfigured"));
        }

        Optional<EffectivePermissions> current = access.currentPermissions();
        if (current.isEmpty()) {
            // Account deleted or disabled while signed in.
            endSession(request);
            rejectUnauthenticated(request, response);
            return false;
        }
        EffectivePermissions permissions = current.get();

        if (sessionPredatesCredentialChange(request, permissions)) {
            endSession(request);
            rejectUnauthenticated(request, response);
            return false;
        }

        if (permissions.mustChangePassword() && !has(method, AllowDuringPasswordChange.class)) {
            if (isApi(request)) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("success", false);
                body.put("message", msg("account.password.mustChange"));
                body.put("passwordChangeRequired", true);
                writeJson(response, HttpServletResponse.SC_FORBIDDEN, body);
            } else {
                response.sendRedirect(request.getContextPath() + "/account/password");
            }
            return false;
        }

        if (!required.isEmpty()
                && required.stream().noneMatch(r -> permissions.has(r.module(), r.action()))) {
            RequiresPermission first = required.iterator().next();
            throw access.denied(first.module(), first.action(), false);
        }
        return true;
    }

    private boolean sessionPredatesCredentialChange(HttpServletRequest request, EffectivePermissions permissions) {
        LocalDateTime changedAt = permissions.credentialsChangedAt();
        HttpSession session = request.getSession(false);
        if (changedAt == null || session == null) {
            return false;
        }
        Object authTime = session.getAttribute(SESSION_AUTH_TIME);
        return !(authTime instanceof LocalDateTime signedInAt) || changedAt.isAfter(signedInAt);
    }

    private void endSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }

    private void rejectUnauthenticated(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (isApi(request)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("message", msg("access.denied.noSession"));
            writeJson(response, HttpServletResponse.SC_UNAUTHORIZED, body);
        } else {
            response.sendRedirect(request.getContextPath() + "/login?expired");
        }
    }

    private void writeJson(HttpServletResponse response, int status, Object body) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }

    private static boolean isApi(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return path.startsWith("/api/");
    }

    private static boolean has(HandlerMethod method, Class<? extends Annotation> type) {
        return AnnotatedElementUtils.hasAnnotation(method.getMethod(), type)
                || AnnotatedElementUtils.hasAnnotation(method.getBeanType(), type);
    }

    private static Set<RequiresPermission> requirements(HandlerMethod method) {
        Set<RequiresPermission> onMethod =
                AnnotatedElementUtils.findMergedRepeatableAnnotations(method.getMethod(), RequiresPermission.class);
        if (!onMethod.isEmpty()) {
            return onMethod;
        }
        return AnnotatedElementUtils.findMergedRepeatableAnnotations(method.getBeanType(), RequiresPermission.class);
    }

    private String msg(String key) {
        return messages.getMessage(key, null, key, LocaleContextHolder.getLocale());
    }
}
