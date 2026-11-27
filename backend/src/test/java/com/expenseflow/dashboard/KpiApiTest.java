package com.expenseflow.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.domain.Role;
import com.expenseflow.domain.User;
import com.expenseflow.repository.RoleRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.JwtTokenService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * NFR-DASH / FR-12 / BR-19 over HTTP with PostgreSQL. Every test builds its own department, manager and
 * employees around a fixed anchor T, so exact numbers can be asserted regardless of other tests' data
 * (finance scope is narrowed with departmentId).
 */
class KpiApiTest extends AbstractIntegrationTest {

    static final Instant T = Instant.parse("2026-10-05T00:00:00Z"); // a Monday

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JwtTokenService tokens;

    long dept;
    User manager;
    User e1;
    User e2;
    User outsider;
    User auditor;

    @BeforeEach
    void fixture() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        dept = jdbc.queryForObject("insert into departments (code, name) values (?, ?) returning id", Long.class,
                "D" + tag, "Dept " + tag);
        manager = user("mgr", Role.MANAGER, null);
        e1 = user("e1", Role.EMPLOYEE, manager);
        e2 = user("e2", Role.EMPLOYEE, manager);
        outsider = user("x", Role.EMPLOYEE, null);
        auditor = user("aud", Role.FINANCE_AUDITOR, null);

        // c1 E1: submitted exactly at T (inclusive), approved by a person after 10 h; 2 lines, 1 violation
        long c1 = claim(e1, T, "APPROVED", "1000.00", "ROUTED", T.plus(Duration.ofHours(10)));
        line(c1, 1, "MEAL", "400.00", "[{\"code\":\"MEAL_DAILY_LIMIT\",\"action\":\"ROUTE\"}]");
        line(c1, 2, "HOTEL", "600.00", null);
        step(c1, "MANAGER", T, T.plus(Duration.ofHours(10)), "APPROVED");
        // c2 E1: last minute of day 1, pending at manager; 2 violations (legacy key "rule" tolerated)
        long c2 = claim(e1, T.plus(Duration.ofMinutes(24 * 60 - 1)), "PENDING_MANAGER", "500.00", "ROUTED", null);
        line(c2, 1, "CAB", "500.00",
                "[{\"code\":\"CAB_TRIP_LIMIT\",\"action\":\"ROUTE\"},{\"rule\":\"RECEIPT_REQUIRED\",\"action\":\"ROUTE\"}]");
        step(c2, "MANAGER", T.plus(Duration.ofMinutes(24 * 60 - 1)), null, null);
        // c3 E2: exactly T+24h (excluded from the 24 h range), auto-approved
        long c3 = claim(e2, T.plus(Duration.ofHours(24)), "APPROVED", "200.00", "AUTO_APPROVED", T.plus(Duration.ofHours(24)));
        line(c3, 1, "MEAL", "200.00", null);
        // c4 E2: day 4, rejected by a person after 24 h (no spend)
        long c4 = claim(e2, T.plus(Duration.ofDays(3)), "REJECTED", "300.00", "ROUTED", T.plus(Duration.ofDays(4)));
        line(c4, 1, "OTHER", "300.00", null);
        step(c4, "MANAGER", T.plus(Duration.ofDays(3)), T.plus(Duration.ofDays(4)), "REJECTED");
        // c5 outsider (same department, other manager): day 7, auto-approved
        long c5 = claim(outsider, T.plus(Duration.ofDays(6)), "APPROVED", "700.00", "AUTO_APPROVED", T.plus(Duration.ofDays(6)));
        line(c5, 1, "TRAVEL", "700.00", null);
        // c6 E1: last hour of day 30, paid; decided after the 30 d range
        long c6 = claim(e1, T.plus(Duration.ofDays(30)).minus(Duration.ofHours(1)), "PAID", "100.00", "ROUTED",
                T.plus(Duration.ofDays(30)).plus(Duration.ofHours(1)));
        line(c6, 1, "CAB", "100.00", null);
        // draft: never counted
        claim(e1, null, "DRAFT", "999.00", null, null);
    }

    // ---- fixture helpers ----

    private User user(String prefix, String role, User mgr) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User u = new User(prefix + " " + tag, prefix + "-" + tag + "@expenseflow.test", "$2a$10$x");
        u.getRoles().add(roles.findById(role).orElseThrow());
        u.setManager(mgr);
        u = users.save(u);
        jdbc.update("update users set department_id = ? where id = ?", dept, u.getId());
        return u;
    }

    private long claim(User emp, Instant submitted, String status, String total, String outcome, Instant decided) {
        return jdbc.queryForObject("insert into claims (code, employee_id, title, status, total_amount, policy_outcome,"
                + " submitted_at, decided_at, paid_at) values (?, ?, 'KPI test', ?, ?::numeric, ?, ?, ?, ?) returning id",
                Long.class, "K-" + UUID.randomUUID().toString().substring(0, 12), emp.getId(), status, total, outcome,
                ts(submitted), ts(decided), "PAID".equals(status) ? ts(decided) : null);
    }

    private void line(long claim, int no, String category, String amount, String violations) {
        jdbc.update("insert into claim_lines (claim_id, line_no, category, expense_date, amount, vendor, nights,"
                + " violations) values (?, ?, ?, date '2026-10-01', ?::numeric, 'Vendor', ?, ?::jsonb)",
                claim, no, category, amount, "HOTEL".equals(category) ? 1 : null, violations);
    }

    private void step(long claim, String level, Instant created, Instant decided, String decision) {
        jdbc.update("insert into approval_steps (claim_id, level, assignee_id, acted_by_id, decision, created_at,"
                + " decided_at) values (?, ?, ?, ?, ?, ?, ?)", claim, level, manager.getId(),
                decision == null ? null : manager.getId(), decision, ts(created), ts(decided));
    }

    private static Timestamp ts(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }

    private JsonNode kpis(User as, Instant from, Instant to, String extra) throws Exception {
        HttpResponse<String> res = get(as, "from=" + enc(from) + "&to=" + enc(to) + extra);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private HttpResponse<String> get(User as, String query) throws Exception {
        String token = tokens.issue(as.getId(), as.getEmail(), as.roleCodes()).value();
        return http.send(HttpRequest.newBuilder(URI.create(url("/api/dashboard/kpis?" + query)))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String enc(Object o) {
        return URLEncoder.encode(String.valueOf(o), StandardCharsets.UTF_8);
    }

    private String deptFilter() {
        return "&departmentId=" + dept;
    }

    // ---- tests ----

    @Test
    void kpis_24h_7d_30d_boundaries() throws Exception {
        JsonNode day = kpis(auditor, T, T.plus(Duration.ofHours(24)), deptFilter());
        assertThat(day.at("/range/bucket").asString()).isEqualTo("HOUR");
        assertThat(day.at("/range/from").asString()).isEqualTo("2026-10-05T00:00:00Z");
        assertThat(day.at("/totals/claims").asLong()).isEqualTo(2);          // c1 (at from), c2; c3 at `to` excluded
        assertThat(day.at("/totals/spend").decimalValue()).isEqualByComparingTo("1500.00");
        assertThat(day.at("/series").size()).isEqualTo(24);
        assertThat(day.at("/series/0/claims").asLong()).isEqualTo(1);
        assertThat(day.at("/series/23/claims").asLong()).isEqualTo(1);

        JsonNode week = kpis(auditor, T, T.plus(Duration.ofDays(7)), deptFilter());
        assertThat(week.at("/range/bucket").asString()).isEqualTo("DAY");
        assertThat(week.at("/series").size()).isEqualTo(7);
        assertThat(week.at("/totals/claims").asLong()).isEqualTo(5);         // c1..c5
        assertThat(week.at("/totals/spend").decimalValue()).isEqualByComparingTo("2400.00"); // c4 rejected excluded
        assertThat(week.at("/totals/autoApprovedRate").asDouble()).isEqualTo(0.4);
        assertThat(week.at("/totals/avgApprovalHours").asDouble()).isEqualTo(17.0); // BR-19: (10 h + 24 h) / 2
        assertThat(week.at("/series/1/claims").asLong()).isEqualTo(1);       // c3 on day 2

        JsonNode month = kpis(auditor, T, T.plus(Duration.ofDays(30)), deptFilter());
        assertThat(month.at("/range/bucket").asString()).isEqualTo("DAY");
        assertThat(month.at("/series").size()).isEqualTo(30);
        assertThat(month.at("/totals/claims").asLong()).isEqualTo(6);
        assertThat(month.at("/series/29/claims").asLong()).isEqualTo(1);     // c6 in the last bucket

        JsonNode quarter = kpis(auditor, T, T.plus(Duration.ofDays(120)), deptFilter());
        assertThat(quarter.at("/range/bucket").asString()).isEqualTo("WEEK");
        assertThat(quarter.at("/series/0/start").asString()).isEqualTo("2026-10-05T00:00:00Z");
    }

    @Test
    void kpis_breakdowns_violations_andApprovalTimeByLevel() throws Exception {
        JsonNode week = kpis(auditor, T, T.plus(Duration.ofDays(7)), deptFilter());
        assertThat(week.at("/scope").asString()).isEqualTo("ALL");
        assertThat(week.at("/totals/violations").asLong()).isEqualTo(3);
        assertThat(week.at("/totals/pendingApprovals").asLong()).isEqualTo(1);
        assertThat(week.at("/violationsByRule").toString()).contains("{\"code\":\"CAB_TRIP_LIMIT\",\"count\":1}")
                .contains("{\"code\":\"MEAL_DAILY_LIMIT\",\"count\":1}").contains("{\"code\":\"RECEIPT_REQUIRED\",\"count\":1}");
        assertThat(week.at("/spendByCategory").toString()).contains("\"category\":\"TRAVEL\"")
                .doesNotContain("\"OTHER\"");                                  // only on the rejected claim
        assertThat(week.at("/spendByDepartment/0/spend").decimalValue()).isEqualByComparingTo("2400.00");
        assertThat(week.at("/spendByMonth/0/month").asString()).isEqualTo("2026-10");
        assertThat(week.at("/approvalTimeByLevel/0/level").asString()).isEqualTo("MANAGER");
        assertThat(week.at("/approvalTimeByLevel/0/avgHours").asDouble()).isEqualTo(17.0);
    }

    @Test
    void kpis_employeeScopeOwn_managerScopeTeam() throws Exception {
        JsonNode own = kpis(e1, T, T.plus(Duration.ofDays(30)), "");
        assertThat(own.at("/scope").asString()).isEqualTo("OWN");
        assertThat(own.at("/totals/claims").asLong()).isEqualTo(3);         // c1, c2, c6 (draft excluded)
        assertThat(own.at("/totals/spend").decimalValue()).isEqualByComparingTo("1600.00");

        JsonNode team = kpis(manager, T, T.plus(Duration.ofDays(30)), "");
        assertThat(team.at("/scope").asString()).isEqualTo("TEAM");
        assertThat(team.at("/totals/claims").asLong()).isEqualTo(5);        // e1 + e2, not the outsider
        assertThat(team.at("/totals/spend").decimalValue()).isEqualByComparingTo("1800.00");

        HttpResponse<String> deptAsEmployee = get(e1, "from=" + enc(T) + "&to=" + enc(T.plus(Duration.ofDays(1)))
                + deptFilter());
        assertThat(deptAsEmployee.statusCode()).isEqualTo(403);
    }

    @Test
    void kpis_invalidRange_400() throws Exception {
        for (String q : List.of("from=" + enc(T) + "&to=" + enc(T),
                "from=" + enc(T.plus(Duration.ofDays(1))) + "&to=" + enc(T),
                "from=" + enc(T) + "&to=" + enc(T.plus(Duration.ofDays(367))),
                "to=" + enc(T), "from=2026-10-05&to=" + enc(T))) {
            HttpResponse<String> res = get(e1, q);
            assertThat(res.statusCode()).as(q).isEqualTo(400);
            assertThat(res.body()).contains("\"code\":\"INVALID_RANGE\"");
        }
        assertThat(get(e1, "from=" + enc(T) + "&to=" + enc(T.plus(Duration.ofDays(366)))).statusCode()).isEqualTo(200);
    }

    @Test
    void kpis_withoutToken_401() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url("/api/dashboard/kpis?from="
                + enc(T) + "&to=" + enc(T.plus(Duration.ofDays(1)))))).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(401);
    }
}
