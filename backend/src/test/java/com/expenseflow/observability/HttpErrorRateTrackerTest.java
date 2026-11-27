package com.expenseflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class HttpErrorRateTrackerTest {

    /** Settable clock. */
    static final class TestClock extends Clock {
        long millis = 1_800_000_000_000L;

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        void advanceSeconds(long s) { millis += s * 1000; }
    }

    @Test
    void noTraffic_isZero() {
        assertThat(new HttpErrorRateTracker(new TestClock()).errorRate5m()).isZero();
    }

    @Test
    void shareOf5xxWithinWindow() {
        TestClock clock = new TestClock();
        HttpErrorRateTracker t = new HttpErrorRateTracker(clock);
        for (int i = 0; i < 9; i++) t.record(false);
        t.record(true);
        assertThat(t.errorRate5m()).isEqualTo(0.1);
        clock.advanceSeconds(120);
        t.record(true);
        t.record(true);
        assertThat(t.errorRate5m()).isEqualTo(3.0 / 12);
    }

    @Test
    void oldBucketsExpireAfterFiveMinutes_andSlotsAreReused() {
        TestClock clock = new TestClock();
        HttpErrorRateTracker t = new HttpErrorRateTracker(clock);
        t.record(true);
        clock.advanceSeconds(299);
        t.record(false);
        assertThat(t.errorRate5m()).isEqualTo(0.5);
        clock.advanceSeconds(1); // first bucket is now 300 s old -> out of window
        assertThat(t.errorRate5m()).isZero();
        clock.advanceSeconds(300 - 1); // lands on the same slot as the 299 s bucket, must not inherit its counts
        t.record(true);
        assertThat(t.errorRate5m()).isEqualTo(1.0);
    }
}
