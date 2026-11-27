package com.expenseflow.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTest {

    static final class TestClock extends Clock {
        Instant now = Instant.parse("2026-11-27T12:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private final TestClock clock = new TestClock();
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(5, Duration.ofMinutes(1), clock);

    private void fail(int n, String email, String ip) {
        for (int i = 0; i < n; i++) {
            limiter.checkAllowed(email, ip);
            limiter.recordFailure(email, ip);
        }
    }

    @Test
    void fourFailuresAllowed_fifthBlocksNext_withRetryAfter() {
        fail(4, "a@x.test", "1.1.1.1");
        assertThatCode(() -> limiter.checkAllowed("a@x.test", "1.1.1.1")).doesNotThrowAnyException();
        limiter.recordFailure("a@x.test", "1.1.1.1");
        assertThatThrownBy(() -> limiter.checkAllowed("A@X.test ", "1.1.1.1"))
                .isInstanceOfSatisfying(LoginAttemptLimiter.TooManyAttemptsException.class,
                        e -> assertThat(e.retryAfterSeconds()).isBetween(1L, 61L));
    }

    @Test
    void unblocksWhenOldestFailureLeavesWindow() {
        fail(5, "a@x.test", "ip");
        clock.advance(Duration.ofSeconds(59));
        assertThatThrownBy(() -> limiter.checkAllowed("a@x.test", "ip"))
                .isInstanceOf(LoginAttemptLimiter.TooManyAttemptsException.class);
        clock.advance(Duration.ofSeconds(1));
        assertThatCode(() -> limiter.checkAllowed("a@x.test", "ip")).doesNotThrowAnyException();
    }

    @Test
    void successClearsCounter_andKeysAreIndependent() {
        fail(4, "a@x.test", "ip");
        limiter.recordSuccess("a@x.test", "ip");
        fail(4, "a@x.test", "ip");
        assertThatCode(() -> limiter.checkAllowed("a@x.test", "ip")).doesNotThrowAnyException();

        fail(5, "b@x.test", "ip");
        assertThatCode(() -> limiter.checkAllowed("b@x.test", "other-ip")).doesNotThrowAnyException();
        assertThatCode(() -> limiter.checkAllowed("c@x.test", "ip")).doesNotThrowAnyException();
    }
}
