package com.attendance.controller;

import com.attendance.dto.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

/**
 * Turns exceptions from the REST API into {@link ApiResponse} errors with a real HTTP status.
 * <p>
 * Only applies to {@code /api/**}; for page requests the exception is re-thrown so
 * Spring Security / Spring Boot show the normal error page (e.g. templates/error/403.html).
 */
@RestControllerAdvice
@Slf4j
public class ApiExceptionHandler {

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> accessDenied(AccessDeniedException e, HttpServletRequest request) {
        rethrowForPages(e, request);
        log.info("Access denied for {} {}: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
        return error(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> invalid(MethodArgumentNotValidException e, HttpServletRequest request)
            throws MethodArgumentNotValidException {
        rethrowForPages(e, request);
        String message = e.getBindingResult().getFieldErrors().stream()
                .map(ApiExceptionHandler::describe)
                .collect(Collectors.joining("; "));
        return error(HttpStatus.BAD_REQUEST, message.isBlank() ? "Invalid request" : message);
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<ApiResponse<Void>> badRequest(Exception e, HttpServletRequest request) throws Exception {
        rethrowForPages(e, request);
        return error(HttpStatus.BAD_REQUEST, "Invalid request parameters");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponse<Void>> methodNotAllowed(HttpRequestMethodNotSupportedException e,
                                                              HttpServletRequest request) throws Exception {
        rethrowForPages(e, request);
        return error(HttpStatus.METHOD_NOT_ALLOWED, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> tooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        rethrowForPages(e, request);
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "The uploaded file is too large");
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(DataIntegrityViolationException e, HttpServletRequest request) {
        rethrowForPages(e, request);
        log.warn("Data integrity violation on {} {}: {}", request.getMethod(), request.getRequestURI(),
                e.getMostSpecificCause().getMessage());
        return error(HttpStatus.CONFLICT, "The change conflicts with existing data");
    }

    /**
     * Business errors thrown as plain RuntimeException / IllegalArgumentException / IllegalStateException
     * carry a message meant for the user. Anything else is unexpected: log it, return a generic message.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> other(Exception e, HttpServletRequest request) throws Exception {
        rethrowForPages(e, request);
        Class<?> type = e.getClass();
        if (type == RuntimeException.class || type == IllegalArgumentException.class
                || type == IllegalStateException.class) {
            log.warn("{} {} failed: {}", request.getMethod(), request.getRequestURI(), e.getMessage());
            return error(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        log.error("Unexpected error on {} {}", request.getMethod(), request.getRequestURI(), e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected server error");
    }

    private static <E extends Exception> void rethrowForPages(E e, HttpServletRequest request) throws E {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (!path.startsWith("/api/")) {
            throw e;
        }
    }

    private static String describe(FieldError error) {
        return error.getField() + ": " + error.getDefaultMessage();
    }

    private static ResponseEntity<ApiResponse<Void>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.error(message));
    }
}
