package com.expenseflow.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;

import com.expenseflow.api.ApiError;
import com.expenseflow.api.ApiException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.security.AuthDtos.LoginRequest;
import com.expenseflow.security.AuthDtos.LoginResponse;
import com.expenseflow.security.AuthDtos.SignupRequest;
import com.expenseflow.security.AuthDtos.UserView;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final LoginAttemptLimiter limiter;

    public AuthController(AuthService auth, LoginAttemptLimiter limiter) {
        this.auth = auth;
        this.limiter = limiter;
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView signup(@Valid @RequestBody SignupRequest req) {
        return auth.signup(req);
    }

    /** Throttled per email + client IP (T-507): 429 TOO_MANY_REQUESTS with Retry-After when blocked. */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest req, HttpServletRequest http) {
        String ip = http.getRemoteAddr();
        limiter.checkAllowed(req.email(), ip);
        try {
            LoginResponse res = auth.login(req);
            limiter.recordSuccess(req.email(), ip);
            return res;
        } catch (ApiException e) {
            if (e.status() == HttpStatus.UNAUTHORIZED) {
                limiter.recordFailure(req.email(), ip);
            }
            throw e;
        }
    }

    @ExceptionHandler(LoginAttemptLimiter.TooManyAttemptsException.class)
    ResponseEntity<ApiError> tooManyAttempts(LoginAttemptLimiter.TooManyAttemptsException e) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(e.retryAfterSeconds()))
                .body(ApiError.of("TOO_MANY_REQUESTS", "Too many login attempts. Try again later."));
    }

    /** Not covered by the public /api/auth/** rule: see SecurityConfig. */
    @GetMapping("/me")
    public UserView me(@AuthenticationPrincipal Jwt jwt) {
        return auth.me(jwt.getSubject());
    }
}
