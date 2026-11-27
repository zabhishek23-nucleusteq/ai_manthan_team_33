package com.expenseflow.mockbank.fault;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Runtime fault settings.
 *
 * @param errorRate probability (0..1) that a request to /bank/** gets the fault of {@code mode}
 * @param latencyMs extra latency added to every /bank/** request
 * @param mode      ok = no failures, 500 = HTTP 500, timeout = hang then 504
 */
public record FaultConfig(
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double errorRate,
        @NotNull @Min(0) @Max(120_000) Long latencyMs,
        @NotNull FaultMode mode) {
}
