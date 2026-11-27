package com.aimanthan.expenseflow;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

/**
 * Base class for full-stack tests: real HTTP port + PostgreSQL 18 (Testcontainers, needs Docker running).
 * The container is shared across test classes through Spring's context cache.
 * {@link AutoConfigureMetrics}: Spring Boot disables metrics export in tests; we test /actuator/prometheus.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMetrics
@Import(TestcontainersConfiguration.class)
public abstract class AbstractIntegrationTest {

    @Value("${local.server.port}")
    protected int port;

    protected String url(String path) {
        return "http://localhost:" + port + path;
    }
}
