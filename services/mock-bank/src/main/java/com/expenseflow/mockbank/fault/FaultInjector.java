package com.expenseflow.mockbank.fault;

import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.DoubleSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Holds the current fault settings and applies them to incoming bank requests. */
@Component
public class FaultInjector {

    private static final Logger log = LoggerFactory.getLogger(FaultInjector.class);

    private final FaultConfig defaults;
    private final long timeoutMs;
    private final MeterRegistry meters;
    private final AtomicReference<FaultConfig> current;
    private volatile DoubleSupplier random = () -> ThreadLocalRandom.current().nextDouble();

    public FaultInjector(
            @Value("${mockbank.faults.mode:ok}") String mode,
            @Value("${mockbank.faults.error-rate:1.0}") double errorRate,
            @Value("${mockbank.faults.latency-ms:0}") long latencyMs,
            @Value("${mockbank.faults.timeout-ms:30000}") long timeoutMs,
            MeterRegistry meters) {
        this.defaults = new FaultConfig(errorRate, latencyMs, FaultMode.from(mode));
        this.timeoutMs = timeoutMs;
        this.meters = meters;
        this.current = new AtomicReference<>(defaults);
    }

    public FaultConfig current() {
        return current.get();
    }

    public FaultConfig update(FaultConfig config) {
        current.set(config);
        log.warn("Fault injection updated: mode={} errorRate={} latencyMs={}",
                config.mode().value(), config.errorRate(), config.latencyMs());
        return config;
    }

    public FaultConfig reset() {
        return update(defaults);
    }

    /** Test hook to make the error-rate roll deterministic. */
    void setRandom(DoubleSupplier random) {
        this.random = random;
    }

    /**
     * Adds latency and, depending on the mode and error rate, throws {@link InjectedFaultException}.
     */
    public void apply() {
        FaultConfig cfg = current.get();
        if (cfg.latencyMs() > 0) {
            meters.counter("mockbank_faults_injected_total", "type", "latency").increment();
            sleep(cfg.latencyMs());
        }
        if (cfg.mode() == FaultMode.OK || random.getAsDouble() >= cfg.errorRate()) {
            return;
        }
        meters.counter("mockbank_faults_injected_total", "type", cfg.mode().value()).increment();
        if (cfg.mode() == FaultMode.TIMEOUT) {
            sleep(timeoutMs);
            throw new InjectedFaultException(504, "BANK_TIMEOUT", "Injected fault: bank did not answer in time");
        }
        throw new InjectedFaultException(500, "BANK_UNAVAILABLE", "Injected fault: bank internal error");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
