package com.expenseflow.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.domain.ApprovalLevel;
import com.expenseflow.domain.ApprovalStep;
import com.expenseflow.domain.Claim;
import com.expenseflow.domain.Role;
import com.expenseflow.domain.User;
import com.expenseflow.repository.ApprovalStepRepository;
import com.expenseflow.repository.ClaimRepository;
import com.expenseflow.repository.RoleRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.JwtTokenService;

/**
 * T-018 / FR-13 / NFR-AUDIT: append-only audit trail enforced by the database, written in the caller's
 * transaction, and the claim timeline with its row rule. Data is committed (no @Transactional) because the
 * HTTP calls run on server threads.
 */
class AuditTrailTest extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired AuditService audit;
    @Autowired AuditEventRepository events;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired ClaimRepository claims;
    @Autowired ApprovalStepRepository steps;
    @Autowired JwtTokenService tokens;

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    private User newUser(String name, String... roleCodes) {
        User u = new User(name, uid() + "@expenseflow.test", "{noop}x");
        for (String r : roleCodes) {
            u.getRoles().add(roles.findById(r).orElseThrow());
        }
        return users.save(u);
    }

    private Claim newClaim(User owner) {
        return claims.save(new Claim("EXP-T-" + uid(), owner, "Client visit"));
    }

    private AuditEvent recordInTx(String action, Claim claim) {
        return tx.execute(s -> audit.record(action, AuditService.CLAIM, claim.getId(),
                Map.of("status", "DRAFT"), Map.of("status", "PENDING_MANAGER")));
    }

    private String token(User u) {
        return tokens.issue(u.getId(), u.getEmail(), u.roleCodes()).value();
    }

    private HttpResponse<String> getAudit(Claim claim, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url("/api/claims/" + claim.getId() + "/audit")))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    // --- append-only (failure-modes "audit trail", invariant 6) ---

    @Test
    void auditEvents_updateRejectedByDb() {
        AuditEvent e = recordInTx("CLAIM_CREATED", newClaim(newUser("Priya", Role.EMPLOYEE)));
        assertThatThrownBy(() -> jdbc.update("update audit_events set action = 'TAMPERED' where id = ?", e.getId()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("append-only");
        assertThat(jdbc.queryForObject("select action from audit_events where id = ?", String.class, e.getId()))
                .isEqualTo("CLAIM_CREATED");
    }

    @Test
    void auditEvents_deleteAndTruncateRejectedByDb() {
        AuditEvent e = recordInTx("CLAIM_CREATED", newClaim(newUser("Priya", Role.EMPLOYEE)));
        assertThatThrownBy(() -> jdbc.update("delete from audit_events where id = ?", e.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.execute("truncate audit_events"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from audit_events where id = ?", Integer.class, e.getId())).isEqualTo(1);
    }

    // --- joins the caller's transaction ---

    @Test
    void record_withoutTransaction_failsFast() {
        Claim claim = newClaim(newUser("Priya", Role.EMPLOYEE));
        assertThatThrownBy(() -> audit.record("CLAIM_CREATED", AuditService.CLAIM, claim.getId(), null, null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void record_rollsBackWithTheCallersChange() {
        Claim claim = newClaim(newUser("Priya", Role.EMPLOYEE));
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            audit.record("CLAIM_SUBMITTED", AuditService.CLAIM, claim.getId(), null, Map.of("status", "PENDING_MANAGER"));
            throw new IllegalStateException("business step failed after the audit write");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(events.findByEntityTypeAndEntityIdOrderByAtAscIdAsc(AuditService.CLAIM, claim.getId().toString())).isEmpty();
    }

    @Test
    void record_storesBeforeAfterJsonAndSystemActor() {
        Claim claim = newClaim(newUser("Priya", Role.EMPLOYEE));
        AuditEvent e = recordInTx("CLAIM_SUBMITTED", claim);
        assertThat(e.getActorId()).as("no signed-in user = system").isNull();
        assertThat(jdbc.queryForObject("select after ->> 'status' from audit_events where id = ?", String.class, e.getId()))
                .isEqualTo("PENDING_MANAGER");
        assertThat(jdbc.queryForObject("select before ->> 'status' from audit_events where id = ?", String.class, e.getId()))
                .isEqualTo("DRAFT");
    }

    // --- events from other modules (L2's PolicyRuleChangedEvent shape) ---

    record RuleChanged(String code, Long actorId, Object before, Object after) implements AuditableChange {
        public String action() { return "POLICY_RULE_UPDATED"; }
        public String entityType() { return AuditService.POLICY_RULE; }
        public Object entityId() { return code; }
    }

    @Autowired org.springframework.context.ApplicationEventPublisher publisher;

    @Test
    void auditableChange_publishedInTx_recordedWithEventActor() {
        User head = newUser("Farhan", Role.EMPLOYEE, Role.FINANCE_HEAD);
        String code = "MEAL_DAILY_LIMIT_" + uid();
        tx.executeWithoutResult(s -> publisher.publishEvent(
                new RuleChanged(code, head.getId(), Map.of("limit", 1500), Map.of("limit", 1800))));

        List<AuditEvent> rows = events.findByEntityTypeAndEntityIdOrderByAtAscIdAsc(AuditService.POLICY_RULE, code);
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getAction()).isEqualTo("POLICY_RULE_UPDATED");
        assertThat(rows.get(0).getActorId()).isEqualTo(head.getId());
        assertThat(jdbc.queryForObject("select (before ->> 'limit') || '->' || (after ->> 'limit') from audit_events where id = ?",
                String.class, rows.get(0).getId())).isEqualTo("1500->1800");
    }

    @Test
    void auditableChange_rollsBackWithPublisher_andFailsOutsideTx() {
        String code = "CAB_TRIP_LIMIT_" + uid();
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            publisher.publishEvent(new RuleChanged(code, null, null, Map.of("limit", 2500)));
            throw new IllegalStateException("rule update failed");
        })).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> publisher.publishEvent(new RuleChanged(code, null, null, null)))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(events.findByEntityTypeAndEntityIdOrderByAtAscIdAsc(AuditService.POLICY_RULE, code)).isEmpty();
    }

    // --- idempotent replay writes one audit event (failure-modes row 1, base for T-016) ---

    @Test
    void replayWithSameKey_writesOneAuditEvent_withActor() throws Exception {
        User owner = newUser("Priya", Role.EMPLOYEE);
        Claim claim = newClaim(owner);
        String key = UUID.randomUUID().toString();
        String json = "{\"ref\":\"audit-" + uid() + "\",\"claimId\":" + claim.getId() + ",\"note\":\"x\"}";
        for (int i = 0; i < 2; i++) {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url("/api/test-probe/idempotent")))
                    .header("Authorization", "Bearer " + token(owner))
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", key)
                    .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(res.statusCode()).isEqualTo(201);
        }
        List<AuditEvent> rows = events.findByEntityTypeAndEntityIdOrderByAtAscIdAsc(AuditService.CLAIM, claim.getId().toString());
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getActorId()).isEqualTo(owner.getId());
    }

    // --- GET /api/claims/{id}/audit row rule (rbac.md) ---

    @Test
    void timeline_ownerSeesEventsInOrderWithActorAndJson() throws Exception {
        User owner = newUser("Priya Timeline", Role.EMPLOYEE);
        Claim claim = newClaim(owner);
        recordInTx("CLAIM_CREATED", claim);
        recordInTx("CLAIM_SUBMITTED", claim);
        tx.executeWithoutResult(s -> audit.record("CLAIM_APPROVED", AuditService.CLAIM, claim.getId(),
                null, Map.of("status", "APPROVED"), owner.getId()));

        HttpResponse<String> res = getAudit(claim, token(owner));
        assertThat(res.statusCode()).isEqualTo(200);
        String body = res.body();
        assertThat(body.indexOf("CLAIM_CREATED")).isLessThan(body.indexOf("CLAIM_SUBMITTED"));
        assertThat(body.indexOf("CLAIM_SUBMITTED")).isLessThan(body.indexOf("CLAIM_APPROVED"));
        assertThat(body).contains("\"entityType\":\"CLAIM\"")
                .contains("\"entityId\":\"" + claim.getId() + "\"")
                .contains("\"after\":{\"status\":\"PENDING_MANAGER\"}")
                .contains("\"onBehalfOf\":{\"id\":" + owner.getId() + ",\"name\":\"Priya Timeline\"");
    }

    @Test
    void timeline_otherEmployeeGets404_sameAsMissingClaim() throws Exception {
        Claim claim = newClaim(newUser("Priya", Role.EMPLOYEE));
        recordInTx("CLAIM_CREATED", claim);
        User stranger = newUser("Stranger", Role.EMPLOYEE, Role.MANAGER);

        HttpResponse<String> res = getAudit(claim, token(stranger));
        assertThat(res.statusCode()).isEqualTo(404);
        assertThat(res.body()).contains("\"code\":\"NOT_FOUND\"").doesNotContain("CLAIM_CREATED");

        HttpResponse<String> missing = http.send(HttpRequest.newBuilder(URI.create(url("/api/claims/999999999/audit")))
                .header("Authorization", "Bearer " + token(stranger)).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(missing.body()).isEqualTo(res.body());
    }

    @Test
    void timeline_assignedApproverAuditorAndHeadSeeIt() throws Exception {
        Claim claim = newClaim(newUser("Priya", Role.EMPLOYEE));
        recordInTx("CLAIM_SUBMITTED", claim);
        User manager = newUser("Rahul", Role.EMPLOYEE, Role.MANAGER);
        steps.save(new ApprovalStep(claim, 1, ApprovalLevel.MANAGER, manager));

        assertThat(getAudit(claim, token(manager)).statusCode()).isEqualTo(200);
        assertThat(getAudit(claim, token(newUser("Anita", Role.EMPLOYEE, Role.FINANCE_AUDITOR))).statusCode()).isEqualTo(200);
        assertThat(getAudit(claim, token(newUser("Farhan", Role.EMPLOYEE, Role.FINANCE_HEAD))).statusCode()).isEqualTo(200);
    }

    @Test
    void timeline_superAdminOnly_is403() throws Exception {
        Claim claim = newClaim(newUser("Priya", Role.EMPLOYEE));
        HttpResponse<String> res = getAudit(claim, token(newUser("Admin", Role.SUPER_ADMIN)));
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.body()).contains("\"code\":\"FORBIDDEN\"");
    }
}
