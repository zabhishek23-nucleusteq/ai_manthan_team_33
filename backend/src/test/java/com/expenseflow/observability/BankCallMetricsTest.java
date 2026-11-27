package com.expenseflow.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class BankCallMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final BankCallMetrics metrics = new BankCallMetrics(registry);

    private double payouts(String outcome) {
        return registry.get("payouts").tag("outcome", outcome).counter().count();
    }

    private long calls(String outcome) {
        return registry.get("external.call").tag("target", "bank").tag("outcome", outcome).timer().count();
    }

    @Test
    void bothOutcomesRegisteredAtZero() {
        assertThat(payouts("success")).isZero();
        assertThat(payouts("failure")).isZero();
    }

    @Test
    void successIsTimedAndCounted() {
        assertThat(metrics.timeSubmit(() -> "PF-1")).isEqualTo("PF-1");
        assertThat(payouts("success")).isEqualTo(1);
        assertThat(calls("success")).isEqualTo(1);
        assertThat(payouts("failure")).isZero();
    }

    @Test
    void exceptionCountsAsFailure_andIsRethrown() {
        IllegalStateException boom = new IllegalStateException("bank 504");
        assertThatThrownBy(() -> metrics.timeSubmit(() -> { throw boom; })).isSameAs(boom);
        assertThat(payouts("failure")).isEqualTo(1);
        assertThat(calls("failure")).isEqualTo(1);
    }

    @Test
    void manualRecord() {
        metrics.record(Duration.ofMillis(30), false);
        assertThat(payouts("failure")).isEqualTo(1);
        assertThat(registry.get("external.call").tag("outcome", "failure").timer().totalTime(
                java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(30);
    }
}
