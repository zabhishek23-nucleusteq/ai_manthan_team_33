package com.expenseflow.observability;

import java.time.Duration;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Metrics around the mock-bank payout submit (sre-handbook s2): {@code external_call_seconds{target="bank",
 * outcome}} (histogram, p95 panel + alert) and {@code payouts_total{outcome="success|failure"}} (success-rate
 * alert). Both outcomes are registered at start so the panels show 0 instead of "no data".
 * <p>
 * Usage (T-017, payout submit):
 * <pre>
 * BankResponse r = bankMetrics.timeSubmit(() -> bankClient.submit(file, batchCode));
 * </pre>
 * Any exception (timeout, 5xx) counts as failure and is rethrown unchanged.
 */
@Component
public class BankCallMetrics {

    static final String TARGET = "bank";

    private final Timer success;
    private final Timer failure;
    private final Counter payoutsSuccess;
    private final Counter payoutsFailure;

    public BankCallMetrics(MeterRegistry registry) {
        this.success = timer(registry, "success");
        this.failure = timer(registry, "failure");
        this.payoutsSuccess = Counter.builder("payouts").description("Payout batch submits to the bank")
                .tag("outcome", "success").register(registry);
        this.payoutsFailure = Counter.builder("payouts").description("Payout batch submits to the bank")
                .tag("outcome", "failure").register(registry);
    }

    private static Timer timer(MeterRegistry registry, String outcome) {
        return Timer.builder("external.call").description("Calls to external systems")
                .tag("target", TARGET).tag("outcome", outcome)
                .publishPercentileHistogram().minimumExpectedValue(Duration.ofMillis(5))
                .maximumExpectedValue(Duration.ofSeconds(60)).register(registry);
    }

    /** Times the bank submit and counts the payout outcome. */
    public <T> T timeSubmit(Supplier<T> call) {
        long start = System.nanoTime();
        try {
            T result = call.get();
            success.record(Duration.ofNanos(System.nanoTime() - start));
            payoutsSuccess.increment();
            return result;
        } catch (RuntimeException e) {
            failure.record(Duration.ofNanos(System.nanoTime() - start));
            payoutsFailure.increment();
            throw e;
        }
    }

    /** For callers that judge the outcome themselves (e.g. a 200 response with every line rejected). */
    public void record(Duration took, boolean ok) {
        (ok ? success : failure).record(took);
        (ok ? payoutsSuccess : payoutsFailure).increment();
    }
}
