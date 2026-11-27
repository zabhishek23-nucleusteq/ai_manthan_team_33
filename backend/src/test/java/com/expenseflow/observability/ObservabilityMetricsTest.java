package com.expenseflow.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.domain.Role;
import com.expenseflow.domain.User;
import com.expenseflow.repository.RoleRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.JwtTokenService;

class ObservabilityMetricsTest extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JwtTokenService tokens;
    @Autowired ApprovalQueueMetrics queueMetrics;
    @Autowired PageLoadTelemetry telemetry;

    private User newUser() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User u = new User("obs " + tag, "obs-" + tag + "@expenseflow.test", "$2a$10$x");
        u.getRoles().add(roles.findById(Role.EMPLOYEE).orElseThrow());
        return users.save(u);
    }

    private String prometheus() throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url("/actuator/prometheus"))).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }

    @Test
    void gauges_exposed_perLevel_andReflectPendingSteps() throws Exception {
        User emp = newUser();
        long claim = jdbc.queryForObject("insert into claims (code, employee_id, title, status, total_amount,"
                + " submitted_at) values (?, ?, 'obs', 'PENDING_HEAD', 10, now() - interval '50 hours') returning id",
                Long.class, "OBS-" + UUID.randomUUID().toString().substring(0, 8), emp.getId());
        jdbc.update("insert into approval_steps (claim_id, level, assignee_id, created_at)"
                + " values (?, 'HEAD', ?, now() - interval '49 hours')", claim, emp.getId());
        queueMetrics.invalidate();

        String body = prometheus();
        for (String level : List.of("MANAGER", "AUDITOR", "HEAD")) {
            assertThat(body).contains("expenseflow_approval_queue_size{application=\"expenseflow\",level=\"" + level + "\"}")
                    .contains("expenseflow_oldest_pending_approval_seconds{application=\"expenseflow\",level=\"" + level + "\"}");
        }
        assertThat(queueMetrics.stats("HEAD").size()).isGreaterThanOrEqualTo(1);
        assertThat(queueMetrics.stats("HEAD").oldestSeconds()).isGreaterThan(48 * 3600);
    }

    @Test
    void pageLoad_recordedAsHistogram_withNormalisedRoute() throws Exception {
        String token = tokens.issue(1, "x@e.test", List.of("EMPLOYEE")).value();
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url("/api/telemetry/page-load")))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"route\":\"/claims/42?tab=lines\",\"ms\":850}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(204);
        assertThat(prometheus()).contains("expenseflow_page_load_seconds_bucket{application=\"expenseflow\",route=\"/claims/:id\"")
                .contains("expenseflow_page_load_seconds_count{application=\"expenseflow\",route=\"/claims/:id\"}");

        HttpResponse<String> bad = http.send(HttpRequest.newBuilder(URI.create(url("/api/telemetry/page-load")))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"route\":\"/x\",\"ms\":-1}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(bad.statusCode()).isEqualTo(400);
        HttpResponse<String> anon = http.send(HttpRequest.newBuilder(URI.create(url("/api/telemetry/page-load")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"route\":\"/x\",\"ms\":1}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anon.statusCode()).isEqualTo(401);
    }

    @Test
    void routeNormalisation_keepsCardinalityLow() {
        assertThat(PageLoadTelemetry.normalise("/Claims/DEMO-1004/")).isEqualTo("/claims/:id");
        assertThat(PageLoadTelemetry.normalise("/approvals#top")).isEqualTo("/approvals");
        assertThat(PageLoadTelemetry.normalise("/x/3f2504e0-4f89-11d3-9a0c-0305e82c3301")).isEqualTo("/x/:id");
        assertThat(PageLoadTelemetry.normalise("javascript:alert(1)")).isEqualTo("other");
        assertThat(PageLoadTelemetry.normalise("/with space")).isEqualTo("other");
        try {
            for (int i = 0; i < PageLoadTelemetry.MAX_ROUTES + 5; i++) {
                telemetry.tagFor("/r" + "abcdefghijklmnopqrstuvwxyz".charAt(i % 26) + "/p" + (char) ('a' + i / 26));
            }
            assertThat(telemetry.tagFor("/brand-new-route")).isEqualTo("other");
        } finally {
            telemetry.resetKnownRoutes(); // shared bean: do not leave the cap exhausted for other test classes
        }
    }
}
