package com.expenseflow.seed;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.expenseflow.TestcontainersConfiguration;

/**
 * T-013: the app started with profile "seed" on an empty PostgreSQL 18 seeds once (SeedRunner), exactly like
 * docker-compose. Own application context => own database container.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("seed")
@Import(TestcontainersConfiguration.class)
class SeedTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired SeedService seed;
    @Autowired SeedSummary summary;

    @Value("${local.server.port}")
    int port;

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private long statusCount(String status) {
        return jdbc.queryForObject("select count(*) from claims where status = ?", Long.class, status);
    }

    @Test
    void seedCounts_150users_8departments_3000claims_inUnder60s() throws Exception {
        assertThat(count("select count(*) from departments")).isEqualTo(8);
        assertThat(count("select count(*) from users")).isEqualTo(150);
        assertThat(count("select count(*) from claims")).isEqualTo(3000);
        assertThat(seed.lastSeed()).isNotNull();
        assertThat(seed.lastSeed().millis()).as("seeding < 60 s").isLessThan(60_000);
        // every user except the finance head has a manager; every user has an employee code and bank details
        assertThat(count("select count(*) from users where manager_id is null")).isEqualTo(1);
        assertThat(count("select count(*) from users where employee_code is null or bank_account_no is null or ifsc is null")).isZero();

        // keep the generated summary for docs/testing/seed-summary.md
        Path out = Path.of("target", "seed-summary.md");
        Files.writeString(out, summary.markdown(seed.lastSeed().millis()));
    }

    @Test
    void seedIsIdempotent_secondRunDoesNothing() {
        SeedService.SeedStats again = seed.seedIfEmpty();
        assertThat(again.seeded()).isFalse();
        assertThat(count("select count(*) from claims")).isEqualTo(3000);
        assertThat(count("select count(*) from users")).isEqualTo(150);
    }

    @Test
    void demoUsersCanLogIn() throws Exception {
        HttpClient http = HttpClient.newHttpClient();
        for (String email : List.of("employee@expenseflow.test", "manager@expenseflow.test", "auditor@expenseflow.test",
                "head@expenseflow.test", "admin@expenseflow.test", "manager2@expenseflow.test")) {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/auth/login"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"" + email + "\",\"password\":\"Demo@123\"}")).build();
            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).as(email).isEqualTo(200);
            assertThat(res.body()).contains("accessToken");
        }
        assertThat(count("select count(*) from user_roles r join users u on u.id = r.user_id "
                + "where u.email = 'head@expenseflow.test' and r.role_code in ('FINANCE_HEAD','SUPER_ADMIN')")).isEqualTo(2);
        assertThat(count("select count(*) from user_roles r join users u on u.id = r.user_id "
                + "where u.email = 'admin@expenseflow.test'")).as("admin is SUPER_ADMIN only").isEqualTo(1);
    }

    @Test
    void stateMixMatchesSpecWithin2Points() {
        Map<String, Double> mix = Map.of("PAID", 40.0, "APPROVED", 12.0, "IN_PAYOUT", 3.0, "PENDING_MANAGER", 10.0, "PENDING_AUDITOR", 5.0,
                "PENDING_HEAD", 2.0, "CLARIFICATION_REQUESTED", 3.0, "REJECTED", 5.0, "AUTO_REJECTED", 8.0, "DRAFT", 12.0);
        mix.forEach((status, pct) -> assertThat(100.0 * statusCount(status) / 3000).as(status).isBetween(pct - 2, pct + 2));
    }

    @Test
    void datesRelativeToNow_3to5PercentInLast24h_andPendingOlderThan48h() {
        long last24 = count("select count(*) from claims where coalesce(submitted_at, created_at) >= now() - interval '24 hours'");
        assertThat(100.0 * last24 / 3000).isBetween(3.0, 5.0);
        assertThat(count("select count(*) from claims where coalesce(submitted_at, created_at) >= now() - interval '2 hours'")).isPositive();
        assertThat(count("select count(*) from claims where coalesce(submitted_at, created_at) < now() - interval '50 days'")).isPositive();
        assertThat(count("select count(*) from claims where coalesce(submitted_at, created_at) > now()")).isZero();
        assertThat(count("select count(*) from approval_steps where decision is null and created_at < now() - interval '48 hours'"))
                .as("pending approvals older than 48 h for the alert demo").isGreaterThanOrEqualTo(10);
    }

    @Test
    void plantedDuplicatesBreachesAndBoundaries() {
        assertThat(count("select count(*) from claim_lines l, jsonb_array_elements(l.violations) v where v->>'code' = 'DUPLICATE_LINE'"))
                .isGreaterThanOrEqualTo(60);
        assertThat(count("select count(*) from claim_lines l, jsonb_array_elements(l.violations) v where v->>'code' = 'DUPLICATE_RECEIPT'"))
                .isGreaterThanOrEqualTo(30);
        assertThat(count("select count(*) from claim_lines l, jsonb_array_elements(l.violations) v "
                + "where v->>'code' in ('MEAL_DAILY_LIMIT','HOTEL_NIGHTLY_LIMIT','CAB_TRIP_LIMIT')")).isGreaterThanOrEqualTo(150);
        assertThat(count("select count(*) from claim_lines l, jsonb_array_elements(l.violations) v where v->>'code' = 'WEEKEND_SPEND'")).isPositive();
        assertThat(count("select count(*) from claims where title like 'Boundary check:%'")).isEqualTo(14);
        // exactly-at-limit records pass, 0.01 above fails (spot checks)
        assertThat(jdbc.queryForObject("select policy_outcome from claims where title = 'Boundary check: other 500.00'", String.class)).isEqualTo("AUTO_APPROVED");
        assertThat(jdbc.queryForObject("select policy_outcome from claims where title = 'Boundary check: other 500.01'", String.class)).isEqualTo("AUTO_REJECTED");
        assertThat(jdbc.queryForObject("select policy_outcome from claims where title = 'Boundary check: cab 2000.01'", String.class)).isEqualTo("ROUTED");
    }

    @Test
    void demoClaimsExactlyAsSpecified() {
        Map<String, String> expected = Map.of("DEMO-1001", "DRAFT", "DEMO-1002", "APPROVED", "DEMO-1003", "AUTO_REJECTED",
                "DEMO-1004", "PENDING_MANAGER", "DEMO-1005", "PENDING_AUDITOR", "DEMO-1006", "PENDING_HEAD",
                "DEMO-1007", "CLARIFICATION_REQUESTED", "DEMO-1008", "APPROVED", "DEMO-1009", "PAID", "DEMO-1010", "AUTO_REJECTED");
        expected.forEach((code, status) -> assertThat(jdbc.queryForObject(
                "select c.status from claims c join users u on u.id = c.employee_id where c.code = ? and u.email = 'employee@expenseflow.test'",
                String.class, code)).as(code).isEqualTo(status));
        assertThat(jdbc.queryForObject("select total_amount from claims where code = 'DEMO-1005'", java.math.BigDecimal.class)).isEqualByComparingTo("30000");
        assertThat(jdbc.queryForObject("select total_amount from claims where code = 'DEMO-1006'", java.math.BigDecimal.class)).isEqualByComparingTo("120000");
        assertThat(jdbc.queryForObject("select policy_result::text from claims where code = 'DEMO-1003'", String.class)).contains("RECEIPT_REQUIRED");
        assertThat(jdbc.queryForObject("select policy_result::text from claims where code = 'DEMO-1004'", String.class)).contains("WEEKEND_SPEND");
        assertThat(jdbc.queryForObject("select policy_result::text from claims where code = 'DEMO-1010'", String.class)).contains("DUPLICATE_RECEIPT");
        assertThat(count("select count(*) from payout_items i join claims c on c.id = i.claim_id where c.code = 'DEMO-1009'")).isEqualTo(1);
        // DEMO-1005 waits for the auditor demo login, DEMO-1006 for the head
        assertThat(jdbc.queryForObject("select u.email from approval_steps s join claims c on c.id = s.claim_id join users u on u.id = s.assignee_id "
                + "where c.code = 'DEMO-1005' and s.decision is null", String.class)).isEqualTo("auditor@expenseflow.test");
        assertThat(jdbc.queryForObject("select u.email from approval_steps s join claims c on c.id = s.claim_id join users u on u.id = s.assignee_id "
                + "where c.code = 'DEMO-1006' and s.decision is null", String.class)).isEqualTo("head@expenseflow.test");
    }

    @Test
    void dataIsConsistent() {
        // every PAID / IN_PAYOUT claim is in exactly one batch; pending claims have exactly one open step
        assertThat(count("select count(*) from claims c where c.status in ('PAID','IN_PAYOUT') "
                + "and not exists (select 1 from payout_items i where i.claim_id = c.id)")).isZero();
        assertThat(count("select count(*) from claims c where c.status like 'PENDING_%' "
                + "and (select count(*) from approval_steps s where s.claim_id = c.id and s.decision is null) <> 1")).isZero();
        // approver is never the claimant (BR-15) and auto-rejected claims have a REJECT violation
        assertThat(count("select count(*) from approval_steps s join claims c on c.id = s.claim_id where s.assignee_id = c.employee_id")).isZero();
        assertThat(count("select count(*) from claims c where c.status = 'AUTO_REJECTED' and c.policy_result::text not like '%REJECT%'")).isZero();
        // sample audit only on auto-approved claims, ~10%
        assertThat(count("select count(*) from claims where sample_audit <> 'NONE' and policy_outcome <> 'AUTO_APPROVED'")).isZero();
        assertThat(count("select count(*) from claims where sample_audit <> 'NONE'")).isPositive();
    }
}
