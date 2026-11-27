package com.expenseflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.domain.PolicyRule;
import com.expenseflow.policy.PolicyEventsProbe;
import com.expenseflow.policy.PolicyRuleChangedEvent;
import com.expenseflow.repository.PolicyRuleRepository;
import com.expenseflow.security.JwtTokenService;

/** FR-5 + rbac.md: GET for MGR/AUD/HEAD/ADMIN, PUT for FINANCE_HEAD only, audited before/after. Real HTTP + real JWTs. */
class PolicyRuleApiTest extends AbstractIntegrationTest {

    @Autowired JwtTokenService tokens;
    @Autowired PolicyRuleRepository rules;
    @Autowired PolicyEventsProbe probe;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired com.expenseflow.repository.UserRepository users;

    private Long actorId;

    /** A real user row: rule changes are audited and audit_events.actor_id references users(id). */
    private long actor() {
        if (actorId == null) {
            String email = "policy-head-" + java.util.UUID.randomUUID() + "@expenseflow.test";
            actorId = users.save(new com.expenseflow.domain.User("Policy Test Head", email, "x")).getId();
        }
        return actorId;
    }

    private String token(String role) {
        return tokens.issue(actor(), role.toLowerCase() + "@expenseflow.test", List.of("EMPLOYEE", role)).value();
    }

    private HttpResponse<String> get(String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url("/api/policy-rules"))).GET();
        if (token != null) b.header("Authorization", "Bearer " + token);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String code, String body, String token) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url("/api/policy-rules/" + code)))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    @AfterEach
    void restoreCabLimit() throws Exception {
        // the HTTP request commits, so put the seeded values back for other tests
        put("CAB_TRIP_LIMIT", "{\"limitAmount\":2000,\"action\":\"ROUTE\",\"enabled\":true}", token("FINANCE_HEAD"));
    }

    @Test
    void list_asManager_returnsAllRulesInContractShape() throws Exception {
        HttpResponse<String> res = get(token("MANAGER"));
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"code\":\"MEAL_DAILY_LIMIT\"").contains("\"code\":\"WEEKEND_SPEND\"")
                .contains("\"tier1Cities\"")
                .contains("\"code\":\"SAMPLE_AUDIT_RATE\"").contains("\"unit\":\"RATE\"").contains("\"action\":\"CONFIG\"");
    }

    @Test
    void list_asEmployee_403_and_withoutToken_401() throws Exception {
        HttpResponse<String> emp = get(tokens.issue(990002, "e@expenseflow.test", List.of("EMPLOYEE")).value());
        assertThat(emp.statusCode()).isEqualTo(403);
        assertThat(emp.body()).contains("FORBIDDEN");
        assertThat(get(null).statusCode()).isEqualTo(401);
    }

    @Test
    void policyRules_put_403_forManager() throws Exception {
        HttpResponse<String> res = put("CAB_TRIP_LIMIT", "{\"limitAmount\":1}", token("MANAGER"));
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(rules.findById("CAB_TRIP_LIMIT").orElseThrow().getLimitAmount()).isEqualByComparingTo("2000");
    }

    @Test
    void put_asFinanceHead_updatesRule_andPublishesBeforeAfterAuditEvent() throws Exception {
        probe.events.clear();
        HttpResponse<String> res = put("CAB_TRIP_LIMIT", "{\"limitAmount\":1800.50}", token("FINANCE_HEAD"));
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"limitAmount\":1800.5");
        assertThat(rules.findById("CAB_TRIP_LIMIT").orElseThrow().getLimitAmount()).isEqualByComparingTo("1800.50");

        // recorded in the append-only audit trail (L1's AuditService, same transaction)
        assertThat(jdbc.queryForObject("select count(*) from audit_events where action = 'POLICY_RULE_UPDATED' "
                + "and entity_type = 'POLICY_RULE' and entity_id = 'CAB_TRIP_LIMIT' and (after->>'limitAmount')::numeric = 1800.50", Long.class))
                .isEqualTo(1);

        PolicyRuleChangedEvent e = probe.events.stream().filter(x -> x.code().equals("CAB_TRIP_LIMIT")).findFirst().orElseThrow();
        assertThat(e.actorId()).isEqualTo(actor());
        assertThat(new BigDecimal(e.before().get("limitAmount").toString())).isEqualByComparingTo("2000");
        assertThat(new BigDecimal(e.after().get("limitAmount").toString())).isEqualByComparingTo("1800.50");
    }

    @Test
    void put_invalidValues_rejectedWithStableCodes() throws Exception {
        String head = token("FINANCE_HEAD");
        assertThat(put("NOPE", "{\"enabled\":true}", head).statusCode()).isEqualTo(404);

        HttpResponse<String> negative = put(PolicyRule.CAB_TRIP_LIMIT, "{\"limitAmount\":-1}", head);
        assertThat(negative.statusCode()).isEqualTo(400);
        assertThat(negative.body()).contains("VALIDATION_FAILED");

        HttpResponse<String> rate = put(PolicyRule.SAMPLE_AUDIT_RATE, "{\"limitAmount\":1.5}", head);
        assertThat(rate.statusCode()).isEqualTo(422);
        assertThat(rate.body()).contains("POLICY_RULE_INVALID");

        HttpResponse<String> settingAction = put(PolicyRule.AUTO_APPROVE_MAX, "{\"action\":\"REJECT\"}", head);
        assertThat(settingAction.statusCode()).isEqualTo(422);

        assertThat(put(PolicyRule.CAB_TRIP_LIMIT, "{\"action\":\"MAYBE\"}", head).statusCode()).isEqualTo(400);
    }
}
