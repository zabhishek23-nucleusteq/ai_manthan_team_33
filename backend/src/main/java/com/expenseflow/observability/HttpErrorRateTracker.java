package com.expenseflow.observability;

import java.io.IOException;
import java.time.Clock;
import java.util.concurrent.atomic.AtomicLongArray;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sliding 5-minute window of /api/** responses (one bucket per second) so the admin health page can show the
 * share of 5xx responses "right now" - Micrometer's counters are cumulative since start.
 */
@Component
public class HttpErrorRateTracker extends OncePerRequestFilter {

    static final int WINDOW_SECONDS = 300;

    private final AtomicLongArray bucketSecond = new AtomicLongArray(WINDOW_SECONDS);
    private final AtomicLongArray total = new AtomicLongArray(WINDOW_SECONDS);
    private final AtomicLongArray errors = new AtomicLongArray(WINDOW_SECONDS);
    private final Clock clock;

    public HttpErrorRateTracker() {
        this(Clock.systemUTC());
    }

    HttpErrorRateTracker(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean failed = true;
        try {
            chain.doFilter(request, response);
            failed = response.getStatus() >= 500;
        } finally {
            record(failed);
        }
    }

    void record(boolean serverError) {
        long now = clock.millis() / 1000;
        int i = (int) (now % WINDOW_SECONDS);
        long seen = bucketSecond.get(i);
        if (seen != now && bucketSecond.compareAndSet(i, seen, now)) {
            total.set(i, 0);
            errors.set(i, 0);
        }
        total.incrementAndGet(i);
        if (serverError) {
            errors.incrementAndGet(i);
        }
    }

    /** Share (0..1) of 5xx among /api responses in the last 5 minutes; 0 when there was no traffic. */
    public double errorRate5m() {
        long now = clock.millis() / 1000;
        long t = 0;
        long e = 0;
        for (int i = 0; i < WINDOW_SECONDS; i++) {
            if (now - bucketSecond.get(i) < WINDOW_SECONDS) {
                t += total.get(i);
                e += errors.get(i);
            }
        }
        return t == 0 ? 0.0 : (double) e / t;
    }
}
