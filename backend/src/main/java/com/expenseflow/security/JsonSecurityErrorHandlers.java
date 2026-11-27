package com.expenseflow.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * JSON bodies for security failures raised in the filter chain (before controllers run), matching the
 * contract's Error schema: 401 {@code UNAUTHENTICATED}, 403 {@code FORBIDDEN}. Messages are fixed so no
 * detail about the token or user leaks.
 */
public final class JsonSecurityErrorHandlers {

    static final String UNAUTHENTICATED_BODY = "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Authentication required\"}";
    static final String FORBIDDEN_BODY = "{\"code\":\"FORBIDDEN\",\"message\":\"Access denied\"}";

    private JsonSecurityErrorHandlers() {
    }

    public static AuthenticationEntryPoint entryPoint() {
        return (HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) -> {
            res.setHeader("WWW-Authenticate", "Bearer");
            write(res, HttpStatus.UNAUTHORIZED, UNAUTHENTICATED_BODY);
        };
    }

    public static AccessDeniedHandler accessDeniedHandler() {
        return (HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex) ->
                write(res, HttpStatus.FORBIDDEN, FORBIDDEN_BODY);
    }

    private static void write(HttpServletResponse res, HttpStatus status, String body) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding(StandardCharsets.UTF_8.name());
        res.getWriter().write(body);
    }
}
