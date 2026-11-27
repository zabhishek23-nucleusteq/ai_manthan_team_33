package com.expenseflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.security.JwtTokenService;

/**
 * Every series the Grafana dashboard (infra/grafana/dashboards/expenseflow.json) and the SRE handbook s2 query
 * must exist in /actuator/prometheus - otherwise the panels and alerts silently show "no data".
 */
class GrafanaMetricsContractTest extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired JwtTokenService tokens;

    @Test
    void dashboardAndAlertSeriesAreExposed() throws Exception {
        String token = tokens.issue(1, "x@e.test", List.of("EMPLOYEE")).value();
        http.send(HttpRequest.newBuilder(URI.create(url("/api/telemetry/page-load")))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"route\":\"/me/dashboard\",\"ms\":420}")).build(),
                HttpResponse.BodyHandlers.discarding());
        http.send(HttpRequest.newBuilder(URI.create(url("/api/auth/me"))).GET().build(),
                HttpResponse.BodyHandlers.discarding()); // one 401 so http_server_requests exists for /api

        String body = http.send(HttpRequest.newBuilder(URI.create(url("/actuator/prometheus"))).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();

        assertThat(body)
                .contains("http_server_requests_seconds_bucket{")                       // p95 latency panel/alert
                .contains("http_server_requests_seconds_count{")                        // error-rate panel/alert
                .contains("page_load_seconds_bucket{application=\"expenseflow\",route=\"") // route may be capped to "other"
                .contains("approval_queue_size{application=\"expenseflow\",level=\"MANAGER\"}")
                .contains("approval_oldest_pending_age_seconds{application=\"expenseflow\",level=\"HEAD\"}")
                .contains("payouts_total{application=\"expenseflow\",outcome=\"success\"}")
                .contains("payouts_total{application=\"expenseflow\",outcome=\"failure\"}")
                .contains("external_call_seconds_bucket{application=\"expenseflow\",outcome=\"success\",target=\"bank\"")
                // NFR-OBS names stay available
                .contains("expenseflow_approval_queue_size{")
                .contains("expenseflow_page_load_seconds_bucket{");
    }
}
