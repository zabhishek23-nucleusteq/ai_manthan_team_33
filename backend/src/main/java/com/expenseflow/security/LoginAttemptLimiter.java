package com.expenseflow.security;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Brute-force protection for POST /api/auth/login (T-507, OWASP A07): at most {@code maxFailures} failed logins
 * per (email, client IP) within {@code window}; further attempts - even with the right password - are refused
 * with 429 until the oldest failure leaves the window. A successful login clears the key.
 * <p>
 * Keyed on email + IP, not IP alone: behind the frontend's nginx proxy all users share one IP, so an IP-only
 * limit would lock everyone out. In-memory is enough for the single backend instance in compose.
 */
@Component
public class LoginAttemptLimiter {

    static final int MAX_KEYS = 10_000;

    private final int maxFailures;
    private final Duration window;
    private final Clock clock;
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    @Autowired
    public LoginAttemptLimiter(@Value("${app.login.max-failures:5}") int maxFailures,
            @Value("${app.login.window:PT1M}") Duration window) {
        this(maxFailures, window, Clock.systemUTC());
    }

    LoginAttemptLimiter(int maxFailures, Duration window, Clock clock) {
        this.maxFailures = maxFailures;
        this.window = window;
        this.clock = clock;
    }

    /** Thrown before the password is checked when the key is blocked. */
    public static class TooManyAttemptsException extends RuntimeException {
        private final long retryAfterSeconds;

        TooManyAttemptsException(long retryAfterSeconds) {
            super("Too many login attempts");
            this.retryAfterSeconds = retryAfterSeconds;
        }

        public long retryAfterSeconds() {
            return retryAfterSeconds;
        }
    }

    public void checkAllowed(String email, String ip) {
        Deque<Instant> q = failures.get(key(email, ip));
        if (q == null) {
            return;
        }
        Instant now = clock.instant();
        synchronized (q) {
            prune(q, now);
            if (q.size() >= maxFailures) {
                Instant freeAt = q.peekFirst().plus(window);
                throw new TooManyAttemptsException(Math.max(1, Duration.between(now, freeAt).toSeconds() + 1));
            }
        }
    }

    public void recordFailure(String email, String ip) {
        if (failures.size() > MAX_KEYS) {
            purgeStale();
        }
        Instant now = clock.instant();
        Deque<Instant> q = failures.computeIfAbsent(key(email, ip), k -> new ArrayDeque<>());
        synchronized (q) {
            prune(q, now);
            q.addLast(now);
        }
    }

    public void recordSuccess(String email, String ip) {
        failures.remove(key(email, ip));
    }

    private void prune(Deque<Instant> q, Instant now) {
        while (!q.isEmpty() && !q.peekFirst().plus(window).isAfter(now)) {
            q.pollFirst();
        }
    }

    private void purgeStale() {
        Instant now = clock.instant();
        failures.entrySet().removeIf(e -> {
            synchronized (e.getValue()) {
                prune(e.getValue(), now);
                return e.getValue().isEmpty();
            }
        });
    }

    private static String key(String email, String ip) {
        return (email == null ? "" : email.trim().toLowerCase()) + "|" + ip;
    }
}
