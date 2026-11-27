package com.expenseflow.api;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Test-only endpoint (src/test, never shipped) shaped like the paginated / from-to list APIs, for T-603.
 * Top-level on purpose: Spring Boot's test scan skips classes nested in test classes.
 */
@RestController
class ParamProbeController {

    @GetMapping("/api/test-probe/params/{id}")
    Map<String, Object> params(@PathVariable Long id,
                               @RequestParam @Min(0) int page,
                               @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
                               @RequestParam(required = false) Instant from) {
        return Map.of("id", id, "page", page, "size", size);
    }
}
