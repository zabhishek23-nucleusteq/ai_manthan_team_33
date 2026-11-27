package com.expenseflow.idempotency;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerExecutionChain;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.ServletRequestPathUtils;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.expenseflow.api.ApiError;
import com.expenseflow.api.ErrorCodes;
import com.expenseflow.audit.AuditService;
import com.expenseflow.idempotency.IdempotencyStore.Entry;
import com.expenseflow.idempotency.IdempotencyStore.Status;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

/**
 * Enforces {@link Idempotent} (NFR-IDEM, failure-modes "double click / retry after timeout"). Runs after the
 * Spring Security chain (default filter order), so the user is known: keys are scoped per user. Only requests
 * whose controller method carries {@link Idempotent} are touched; everything else passes straight through.
 */
@Component
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final int MIN_KEY = 8;
    private static final int MAX_KEY = 64;

    private final ObjectProvider<RequestMappingHandlerMapping> handlerMapping;
    private final IdempotencyStore store;
    private final JsonMapper json;

    public IdempotencyFilter(@Qualifier("requestMappingHandlerMapping") ObjectProvider<RequestMappingHandlerMapping> handlerMapping,
                             IdempotencyStore store, JsonMapper json) {
        this.handlerMapping = handlerMapping;
        this.store = store;
        this.json = json;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MUTATING.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Long userId = AuditService.currentUserId();
        if (userId == null || !isIdempotentEndpoint(request)) {
            chain.doFilter(request, response);
            return;
        }
        String key = request.getHeader(Idempotent.HEADER);
        if (key == null || key.isBlank() || key.length() < MIN_KEY || key.length() > MAX_KEY) {
            writeError(response, HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED,
                    "Header " + Idempotent.HEADER + " is required (" + MIN_KEY + "-" + MAX_KEY + " characters)",
                    Map.of("header", Idempotent.HEADER));
            return;
        }
        CachedBodyRequest cached = new CachedBodyRequest(request);
        String hash = requestHash(cached);

        if (!store.tryStart(userId, key, hash)) {
            replayOrRefuse(store.find(userId, key), hash, response);
            return;
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(cached, wrapped);
        } catch (IOException | ServletException | RuntimeException e) {
            store.release(userId, key);
            throw e;
        }
        int status = wrapped.getStatus();
        if (status >= 500) {
            store.release(userId, key);
        } else {
            store.complete(userId, key, status, wrapped.getContentType(),
                    new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8));
        }
        wrapped.copyBodyToResponse();
    }

    private void replayOrRefuse(Optional<Entry> existing, String hash, HttpServletResponse response) throws IOException {
        if (existing.isEmpty()) {
            // released between our insert attempt and the lookup: the first request failed with a 5xx just now
            writeError(response, HttpStatus.CONFLICT, ErrorCodes.CONCURRENT_MODIFICATION,
                    "A request with this " + Idempotent.HEADER + " just failed. Retry.", Map.of());
            return;
        }
        Entry entry = existing.get();
        if (!entry.requestHash().equals(hash)) {
            writeError(response, HttpStatus.UNPROCESSABLE_CONTENT, ErrorCodes.IDEMPOTENCY_KEY_REUSED,
                    "This " + Idempotent.HEADER + " was already used for a different request", Map.of("header", Idempotent.HEADER));
            return;
        }
        if (entry.status() == Status.IN_PROGRESS) {
            writeError(response, HttpStatus.CONFLICT, ErrorCodes.CONCURRENT_MODIFICATION,
                    "A request with this " + Idempotent.HEADER + " is still being processed", Map.of());
            return;
        }
        response.setStatus(entry.responseStatus());
        response.setHeader(Idempotent.REPLAYED_HEADER, "true");
        if (entry.contentType() != null) {
            response.setContentType(entry.contentType());
        }
        if (entry.body() != null && !entry.body().isEmpty()) {
            byte[] bytes = entry.body().getBytes(StandardCharsets.UTF_8);
            response.setContentLength(bytes.length);
            response.getOutputStream().write(bytes);
        }
    }

    private boolean isIdempotentEndpoint(HttpServletRequest request) {
        RequestMappingHandlerMapping mapping = handlerMapping.getIfAvailable();
        if (mapping == null) {
            return false;
        }
        try {
            if (!ServletRequestPathUtils.hasParsedRequestPath(request)) {
                ServletRequestPathUtils.parseAndCache(request);
            }
            HandlerExecutionChain handler = mapping.getHandler(request);
            return handler != null && handler.getHandler() instanceof HandlerMethod method
                    && method.hasMethodAnnotation(Idempotent.class);
        } catch (Exception e) {
            log.debug("No handler resolved for {} {}", request.getMethod(), request.getRequestURI(), e);
            return false;
        }
    }

    private static String requestHash(CachedBodyRequest request) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(request.getMethod().getBytes(StandardCharsets.UTF_8));
            sha.update((byte) ' ');
            sha.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
            if (request.getQueryString() != null) {
                sha.update(("?" + request.getQueryString()).getBytes(StandardCharsets.UTF_8));
            }
            sha.update((byte) '\n');
            sha.update(request.body());
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void writeError(HttpServletResponse response, HttpStatus status, String code, String message,
                            Map<String, Object> details) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.writeValueAsString(new ApiError(code, message, details)));
    }
}
