package com.expenseflow.approval;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
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

/** FR-9 / FR-10 over HTTP with PostgreSQL; each test has its own department, users and claims. */
class AuditQueueApiTest extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JwtTokenService tokens;

    long dept;
    User auditor;
    User otherAuditor;
    User manager;
    User employee;
    long q1; // PENDING_AUDITOR, mine, 30,000, MEAL violation, 72 h old
    long q2; // PENDING_AUDITOR, mine, 1,20,000, CAB, 1 h old
    long q3; // PENDING_AUDITOR, assigned to the other auditor
    long q4; // AUTO_APPROVED sample audit, 900, 10 h old
    long own; // auditor's own claim with a pending sample: never in the queue

    @BeforeEach
    void fixture() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        dept = jdbc.queryForObject("insert into departments (code, name) values (?, ?) returning id", Long.class,
                "Q" + tag, "Queue " + tag);
        auditor = user("aud", Role.FINANCE_AUDITOR, null);
        otherAuditor = user("aud2", Role.FINANCE_AUDITOR, null);
        manager = user("mgr", Role.MANAGER, null);
        employee = user("emp", Role.EMPLOYEE, manager);

        Instant now = Instant.now();
        q1 = claim(employee, "PENDING_AUDITOR", "30000.00", now.minus(Duration.ofHours(72)), "NONE", null);
        line(q1, "MEAL", "30000.00", "[{\"code\":\"MEAL_DAILY_LIMIT\",\"action\":\"ROUTE\"}]");
        step(q1, auditor, now.minus(Duration.ofHours(70)));
        q2 = claim(employee, "PENDING_AUDITOR", "120000.00", now.minus(Duration.ofHours(1)), "NONE", null);
        line(q2, "CAB", "120000.00", null);
        step(q2, auditor, now.minus(Duration.ofMinutes(50)));
        q3 = claim(employee, "PENDING_AUDITOR", "40000.00", now.minus(Duration.ofHours(5)), "NONE", null);
        line(q3, "OTHER", "40000.00", null);
        step(q3, otherAuditor, now.minus(Duration.ofHours(4)));
        q4 = claim(employee, "APPROVED", "900.00", now.minus(Duration.ofHours(10)), "PENDING", "AUTO_APPROVED");
        line(q4, "MEAL", "900.00", null);
        own = claim(auditor, "APPROVED", "500.00", now.minus(Duration.ofHours(3)), "PENDING", "AUTO_APPROVED");
        line(own, "MEAL", "500.00", null);
    }

    private User user(String prefix, String role, User mgr) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User u = new User(prefix + " " + tag, prefix + "-" + tag + "@expenseflow.test", "$2a$10$x");
        u.getRoles().add(roles.findById(role).orElseThrow());
        u.setManager(mgr);
        u = users.save(u);
        jdbc.update("update users set department_id = ? where id = ?", dept, u.getId());
        return u;
    }

    private long claim(User emp, String status, String total, Instant submitted, String sample, String outcome) {
        Instant decided = "APPROVED".equals(status) ? submitted : null;
        return jdbc.queryForObject("insert into claims (code, employee_id, title, status, total_amount, submitted_at,"
                + " decided_at, sample_audit, policy_outcome) values (?, ?, 'Queue test', ?, ?::numeric, ?, ?, ?, ?)"
                + " returning id", Long.class, "Q-" + UUID.randomUUID().toString().substring(0, 12), emp.getId(),
                status, total, Timestamp.from(submitted), decided == null ? null : Timestamp.from(decided), sample, outcome);
    }

    private void line(long claim, String category, String amount, String violations) {
        jdbc.update("insert into claim_lines (claim_id, line_no, category, expense_date, amount, vendor, violations)"
                + " values (?, 1, ?, date '2026-11-20', ?::numeric, 'Vendor', ?::jsonb)", claim, category, amount, violations);
    }

    private void step(long claim, User assignee, Instant created) {
        jdbc.update("insert into approval_steps (claim_id, level, assignee_id, created_at) values (?, 'AUDITOR', ?, ?)",
                claim, assignee.getId(), Timestamp.from(created));
    }

    private String token(User u) {
        return tokens.issue(u.getId(), u.getEmail(), u.roleCodes()).value();
    }

    private HttpResponse<String> send(String method, String path, User as, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).method(method,
                body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", "Bearer " + token(as))
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) b.header("Content-Type", "application/json");
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode queue(String query) throws Exception {
        HttpResponse<String> res = send("GET", "/api/audit-queue?departmentId=" + dept + query, auditor, null);
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        return json.readTree(res.body());
    }

    private static String ids(JsonNode page) {
        StringBuilder sb = new StringBuilder();
        page.get("items").forEach(i -> sb.append(i.get("claimId").asLong()).append(','));
        return sb.toString();
    }

    @Test
    void queue_filters() throws Exception {
        JsonNode all = queue("");
        assertThat(all.get("total").asLong()).isEqualTo(3);                 // q1, q2, q4 - not q3 (other auditor), not own
        assertThat(ids(all)).isEqualTo(q1 + "," + q4 + "," + q2 + ",");      // default: oldest submitted first
        assertThat(all.at("/items/0/level").asString()).isEqualTo("AUDITOR");
        assertThat(all.at("/items/1/sampleAudit").asBoolean()).isTrue();

        assertThat(ids(queue("&minAmount=100000"))).isEqualTo(q2 + ",");
        assertThat(ids(queue("&maxAmount=1000"))).isEqualTo(q4 + ",");
        assertThat(ids(queue("&violation=MEAL_DAILY_LIMIT"))).isEqualTo(q1 + ",");
        assertThat(ids(queue("&category=CAB"))).isEqualTo(q2 + ",");
        assertThat(ids(queue("&sampleOnly=true"))).isEqualTo(q4 + ",");
        assertThat(ids(queue("&olderThanHours=48"))).isEqualTo(q1 + ",");
        assertThat(ids(queue("&sort=-total"))).isEqualTo(q2 + "," + q1 + "," + q4 + ",");
        assertThat(queue("&size=1&page=1").get("items").size()).isEqualTo(1);
        assertThat(send("GET", "/api/audit-queue?sort=bogus", auditor, null).statusCode()).isEqualTo(400);
    }

    @Test
    void bulkApprove_partialSuccess() throws Exception {
        HttpResponse<String> res = send("POST", "/api/audit-queue/bulk-approve", auditor,
                "{\"claimIds\":[" + q1 + "," + q3 + ",999999999," + q1 + "],\"comment\":\"bulk ok\"}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        JsonNode r = json.readTree(res.body()).get("results");
        assertThat(r.size()).isEqualTo(3);                                    // duplicate id collapsed
        assertThat(r.get(0).get("ok").asBoolean()).isTrue();
        assertThat(r.get(0).get("status").asString()).isEqualTo("APPROVED");
        assertThat(r.get(1).get("code").asString()).isEqualTo("NOT_ASSIGNED_APPROVER");
        assertThat(r.get(2).get("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(jdbc.queryForObject("select status from claims where id = ?", String.class, q1)).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select status from claims where id = ?", String.class, q3)).isEqualTo("PENDING_AUDITOR");
        assertThat(jdbc.queryForObject("select count(*) from audit_events where entity_type = 'CLAIM' and entity_id = ?"
                + " and action = 'CLAIM_APPROVED'", Long.class, String.valueOf(q1))).isEqualTo(1);

        assertThat(send("POST", "/api/audit-queue/bulk-approve", auditor, "{\"claimIds\":[]}").statusCode())
                .isEqualTo(400);
    }

    @Test
    void sampleAudit_exception_rejects_ok_closes() throws Exception {
        HttpResponse<String> noComment = send("POST", "/api/audit-queue/" + q4 + "/sample-audit", auditor,
                "{\"result\":\"EXCEPTION\"}");
        assertThat(noComment.statusCode()).isEqualTo(400);

        HttpResponse<String> res = send("POST", "/api/audit-queue/" + q4 + "/sample-audit", auditor,
                "{\"result\":\"EXCEPTION\",\"comment\":\"Receipt is from a different city\"}");
        assertThat(res.statusCode()).as(res.body()).isEqualTo(200);
        assertThat(res.body()).contains("\"status\":\"REJECTED\"").contains("\"sampleAudit\":\"EXCEPTION\"");
        assertThat(jdbc.queryForObject("select after->>'reason' from audit_events where entity_id = ? and action ="
                + " 'CLAIM_SAMPLE_AUDIT_EXCEPTION'", String.class, String.valueOf(q4))).isEqualTo("SAMPLE_AUDIT_EXCEPTION");

        HttpResponse<String> again = send("POST", "/api/audit-queue/" + q4 + "/sample-audit", auditor,
                "{\"result\":\"OK\"}");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(send("POST", "/api/audit-queue/" + own + "/sample-audit", auditor, "{\"result\":\"OK\"}").statusCode())
                .isEqualTo(403);
        HttpResponse<String> ok = send("POST", "/api/audit-queue/" + own + "/sample-audit", otherAuditor,
                "{\"result\":\"OK\"}");
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("\"status\":\"APPROVED\"").contains("\"sampleAudit\":\"OK\"");
    }

    @Test
    void queue_manager_403_evenWithInvalidBody() throws Exception {
        HttpResponse<String> get = send("GET", "/api/audit-queue", manager, null);
        assertThat(get.statusCode()).isEqualTo(403);
        assertThat(get.body()).contains("FORBIDDEN");
        assertThat(send("POST", "/api/audit-queue/bulk-approve", manager, "{}").statusCode()).isEqualTo(403);
        assertThat(send("POST", "/api/audit-queue/" + q4 + "/sample-audit", employee, "{\"result\":\"OK\"}")
                .statusCode()).isEqualTo(403);
    }
}
