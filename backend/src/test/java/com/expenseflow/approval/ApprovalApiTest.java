package com.expenseflow.approval;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.domain.Role;
import com.expenseflow.domain.User;
import com.expenseflow.repository.RoleRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** T-019: approval chain, send-back, delegation (FR-6..FR-8, BR-10..BR-16, ADR-001). */
class ApprovalApiTest extends AbstractIntegrationTest {

    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    record Who(long id, String token) {
    }

    private Who manager;

    @BeforeEach
    void approversExist() {
        // the chain needs at least one auditor and one head besides the claimant
        user(null, Role.FINANCE_AUDITOR);
        user(null, Role.FINANCE_HEAD);
        manager = user(null, Role.EMPLOYEE, Role.MANAGER);
    }

    private Who user(Long managerId, String... roleCodes) {
        User u = new User("Test " + uid(), UUID.randomUUID() + "@expenseflow.test", "x");
        for (String r : roleCodes) u.getRoles().add(roles.findById(r).orElseThrow());
        if (managerId != null) u.setManager(users.findById(managerId).orElseThrow());
        u = users.save(u);
        return token(u.getId());
    }

    /** Token for any user id with that user's real roles. */
    private Who token(long id) {
        User u = users.findById(id).orElseThrow();
        List<String> rs = new ArrayList<>();
        jdbc.queryForList("select role_code from user_roles where user_id = ?", String.class, id).forEach(rs::add);
        return new Who(id, tokens.issue(id, u.getEmail(), rs).value());
    }

    private Who employeeOf(Who mgr) {
        return user(mgr.id(), Role.EMPLOYEE);
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private static LocalDate weekday() {
        LocalDate d = LocalDate.now().minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.minusDays(1);
        return d;
    }

    private HttpResponse<String> send(String method, String path, Who w, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Authorization", "Bearer " + w.token()).header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) b.header("Content-Type", "application/json");
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> r) {
        return json.readTree(r.body());
    }

    private long receipt(Who w) throws Exception {
        String boundary = "----ef" + uid();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"r-" + uid() + ".pdf\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("%PDF-1.4\n" + uid() + uid() + "\n%%EOF").getBytes(StandardCharsets.US_ASCII));
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url("/api/receipts")))
                .header("Authorization", "Bearer " + w.token()).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return body(r).get("id").asLong();
    }

    /** Creates and submits a one-line OTHER claim of the given total; returns the claim id. */
    private long routedClaim(Who emp, String amount) throws Exception {
        String line = "{\"category\":\"OTHER\",\"amount\":" + amount + ",\"expenseDate\":\"" + weekday() + "\",\"vendor\":\"V-" + uid()
                + "\",\"receiptId\":" + receipt(emp) + "}";
        HttpResponse<String> c = send("POST", "/api/claims", emp, "{\"title\":\"Chain " + uid() + "\",\"lines\":[" + line + "]}");
        assertThat(c.statusCode()).as(c.body()).isEqualTo(201);
        long id = body(c).get("id").asLong();
        HttpResponse<String> s = send("POST", "/api/claims/" + id + "/submit", emp, null);
        assertThat(s.statusCode()).as(s.body()).isEqualTo(200);
        return id;
    }

    private JsonNode claim(Who w, long id) throws Exception {
        HttpResponse<String> r = send("GET", "/api/claims/" + id, w, null);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return body(r);
    }

    private JsonNode openStep(Who emp, long id) throws Exception {
        for (JsonNode s : claim(emp, id).get("steps")) if ("PENDING".equals(s.get("decision").asString())) return s;
        return null;
    }

    /** Token of whoever is assigned to the open step. */
    private Who assignee(Who emp, long id) throws Exception {
        return token(openStep(emp, id).get("assignee").get("id").asLong());
    }

    private HttpResponse<String> approve(Who w, long id) throws Exception {
        return send("POST", "/api/approvals/" + id + "/approve", w, "{\"comment\":\"ok\"}");
    }

    private String status(HttpResponse<String> r) {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return body(r).get("status").asString();
    }

    private long audit(long claimId, String action) {
        return jdbc.queryForObject("select count(*) from audit_events where entity_type = 'CLAIM' and entity_id = ? and action = ?",
                Long.class, Long.toString(claimId), action);
    }

    // ------------------------------------------------------------------ thresholds (BR-10..BR-12)
    @Test
    void threshold_25000_managerOnly() throws Exception {
        Who emp = employeeOf(manager);
        long id = routedClaim(emp, "25000.00");
        assertThat(claim(emp, id).get("status").asString()).isEqualTo("PENDING_MANAGER");
        assertThat(openStep(emp, id).get("assignee").get("id").asLong()).isEqualTo(manager.id());

        JsonNode inbox = body(send("GET", "/api/approvals/inbox", manager, null));
        assertThat(inbox.get("items").findValuesAsString("code")).contains(claim(emp, id).get("code").asString());

        assertThat(status(approve(manager, id))).isEqualTo("APPROVED");
        assertThat(audit(id, "CLAIM_APPROVED")).isEqualTo(1);
    }

    @Test
    void threshold_25000_01_auditor() throws Exception {
        Who emp = employeeOf(manager);
        long id = routedClaim(emp, "25000.01");
        assertThat(status(approve(manager, id))).isEqualTo("PENDING_AUDITOR");
        assertThat(openStep(emp, id).get("level").asString()).isEqualTo("AUDITOR");
        assertThat(status(approve(assignee(emp, id), id))).isEqualTo("APPROVED");
    }

    @Test
    void threshold_100000_01_head() throws Exception {
        Who emp = employeeOf(manager);
        long id = routedClaim(emp, "100000.01");
        assertThat(status(approve(manager, id))).isEqualTo("PENDING_AUDITOR");
        assertThat(status(approve(assignee(emp, id), id))).isEqualTo("PENDING_HEAD");
        assertThat(status(approve(assignee(emp, id), id))).isEqualTo("APPROVED");
        assertThat(audit(id, "CLAIM_APPROVED")).isEqualTo(3);
    }

    // ------------------------------------------------------------------ only the assigned approver (invariant 3)
    @Test
    void approve_notAssigned_403() throws Exception {
        Who emp = employeeOf(manager);
        long id = routedClaim(emp, "6000.00");
        Who otherManager = user(null, Role.EMPLOYEE, Role.MANAGER);
        HttpResponse<String> r = approve(otherManager, id);
        assertThat(r.statusCode()).isEqualTo(403);
        assertThat(body(r).get("code").asString()).isEqualTo("NOT_ASSIGNED_APPROVER");
        // employees cannot reach the approval API at all
        assertThat(approve(emp, id).statusCode()).isEqualTo(403);
    }

    @Test
    void approve_concurrent_oneSucceeds() throws Exception {
        Who emp = employeeOf(manager);
        long id = routedClaim(emp, "6000.00");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        Callable<Integer> call = () -> {
            go.await();
            return approve(manager, id).statusCode();
        };
        Future<Integer> a = pool.submit(call), b = pool.submit(call);
        go.countDown();
        List<Integer> codes = List.of(a.get(), b.get());
        pool.shutdown();
        assertThat(codes).containsOnlyOnce(200);
        assertThat(codes).anyMatch(c -> c == 409 || c == 403);
        assertThat(audit(id, "CLAIM_APPROVED")).isEqualTo(1);
    }

    @Test
    void auditorLevel_isTeamPool_anyAuditorActsOnBehalf() throws Exception {
        Who emp = employeeOf(manager);
        long id = routedClaim(emp, "25000.01");
        assertThat(status(approve(manager, id))).isEqualTo("PENDING_AUDITOR");
        long assigned = openStep(emp, id).get("assignee").get("id").asLong();
        Who otherAuditor = user(null, Role.EMPLOYEE, Role.FINANCE_AUDITOR);   // not the assignee

        JsonNode inbox = body(send("GET", "/api/approvals/inbox?size=100", otherAuditor, null));
        assertThat(inbox.get("items").findValues("claimId").stream().map(JsonNode::asLong)).contains(id);
        JsonNode queue = body(send("GET", "/api/audit-queue?size=100", otherAuditor, null));
        assertThat(queue.toString()).contains("\"" + claim(emp, id).get("code").asString() + "\"");

        assertThat(approve(user(null, Role.EMPLOYEE, Role.MANAGER), id).statusCode()).as("manager is not in the pool").isEqualTo(403);
        assertThat(status(approve(otherAuditor, id))).isEqualTo("APPROVED");
        JsonNode step = claim(emp, id).get("steps").get(1);
        assertThat(step.get("actedBy").get("id").asLong()).isEqualTo(otherAuditor.id());
        assertThat(step.get("onBehalfOf").get("id").asLong()).isEqualTo(assigned);
    }

    // ------------------------------------------------------------------ BR-15 no self-approval
    @Test
    void selfApproval_escalates() throws Exception {
        // an auditor with no manager files a claim: MANAGER level escalates, AUDITOR level never picks the claimant
        Who auditorClaimant = user(null, Role.EMPLOYEE, Role.FINANCE_AUDITOR);
        long id = routedClaim(auditorClaimant, "6000.00");
        JsonNode step = openStep(auditorClaimant, id);
        assertThat(step.get("level").asString()).isEqualTo("AUDITOR");
        assertThat(step.get("assignee").get("id").asLong()).isNotEqualTo(auditorClaimant.id());
        assertThat(audit(id, "APPROVAL_STEP_ESCALATED")).isEqualTo(1);

        HttpResponse<String> self = approve(auditorClaimant, id);
        assertThat(self.statusCode()).isEqualTo(403);
        assertThat(body(self).get("code").asString()).isEqualTo("SELF_APPROVAL_FORBIDDEN");
    }

    // ------------------------------------------------------------------ delegation (FR-8, BR-14)
    private HttpResponse<String> delegate(Who from, Who to, LocalDate start, LocalDate end) throws Exception {
        return send("POST", "/api/delegations", from,
                "{\"delegateId\":" + to.id() + ",\"from\":\"" + start + "\",\"to\":\"" + end + "\"}");
    }

    @Test
    void delegate_actsOnBehalf_andOpenStepsFollow() throws Exception {
        Who emp = employeeOf(manager);
        long id = routedClaim(emp, "6000.00");               // step created before the delegation
        Who deputy = user(null, Role.EMPLOYEE, Role.MANAGER);
        LocalDate today = LocalDate.now(ApproverDirectory.ZONE);
        assertThat(delegate(manager, deputy, today, today.plusDays(3)).statusCode()).isEqualTo(201);

        JsonNode inbox = body(send("GET", "/api/approvals/inbox", deputy, null));
        assertThat(inbox.get("items").findValues("claimId").stream().map(JsonNode::asLong)).contains(id);

        assertThat(status(approve(deputy, id))).isEqualTo("APPROVED");
        JsonNode step = claim(emp, id).get("steps").get(0);
        assertThat(step.get("actedBy").get("id").asLong()).isEqualTo(deputy.id());
        assertThat(step.get("onBehalfOf").get("id").asLong()).isEqualTo(manager.id());
        assertThat(jdbc.queryForObject("select on_behalf_of_id from audit_events where entity_id = ? and action = 'CLAIM_APPROVED'",
                Long.class, Long.toString(id))).isEqualTo(manager.id());
    }

    @Test
    void delegation_toClaimant_escalates() throws Exception {
        Who empManager = user(manager.id(), Role.EMPLOYEE, Role.MANAGER);   // claimant is a manager too
        LocalDate today = LocalDate.now(ApproverDirectory.ZONE);
        assertThat(delegate(manager, empManager, today, today.plusDays(2)).statusCode()).isEqualTo(201);
        long id = routedClaim(empManager, "6000.00");
        JsonNode step = openStep(empManager, id);
        assertThat(step.get("level").asString()).isEqualTo("AUDITOR");
        assertThat(audit(id, "APPROVAL_STEP_ESCALATED")).isEqualTo(1);
    }

    @Test
    void delegation_noChain() throws Exception {
        Who a = user(null, Role.MANAGER), b = user(null, Role.MANAGER), c = user(null, Role.MANAGER);
        LocalDate today = LocalDate.now(ApproverDirectory.ZONE);
        assertThat(delegate(a, b, today, today.plusDays(5)).statusCode()).isEqualTo(201);
        HttpResponse<String> chain = delegate(b, c, today, today.plusDays(5));
        assertThat(chain.statusCode()).isEqualTo(422);
        assertThat(body(chain).get("code").asString()).isEqualTo("DELEGATION_INVALID");
        // other BR-14 checks
        assertThat(delegate(c, user(null, Role.EMPLOYEE), today, today.plusDays(1)).statusCode()).isEqualTo(422); // not approver
        assertThat(delegate(c, user(null, Role.MANAGER), today, today.plusDays(31)).statusCode()).isEqualTo(422);   // > 30 days
        Who aud = user(null, Role.FINANCE_AUDITOR);
        assertThat(delegate(aud, user(null, Role.MANAGER), today, today.plusDays(1)).statusCode()).isEqualTo(422); // lower role
    }

    // ------------------------------------------------------------------ send-back + resubmit (FR-7, BR-16)
    @Test
    void resubmit_restartsAtManager() throws Exception {
        Who emp = employeeOf(manager);
        long id = routedClaim(emp, "25000.01");
        assertThat(status(approve(manager, id))).isEqualTo("PENDING_AUDITOR");
        HttpResponse<String> back = send("POST", "/api/approvals/" + id + "/send-back", assignee(emp, id), "{\"comment\":\"Add the invoice\"}");
        assertThat(status(back)).isEqualTo("CLARIFICATION_REQUESTED");
        assertThat(audit(id, "CLAIM_SENT_BACK")).isEqualTo(1);

        HttpResponse<String> again = send("POST", "/api/claims/" + id + "/submit", emp, null);
        assertThat(status(again)).isEqualTo("PENDING_MANAGER");
        JsonNode step = openStep(emp, id);
        assertThat(step.get("level").asString()).isEqualTo("MANAGER");
        assertThat(step.get("round").asInt()).isEqualTo(2);

        HttpResponse<String> reject = send("POST", "/api/approvals/" + id + "/reject", manager, "{\"comment\":\"no\"}");
        assertThat(reject.statusCode()).as("comment min length 3").isEqualTo(400);
        assertThat(status(send("POST", "/api/approvals/" + id + "/reject", manager, "{\"comment\":\"Not allowed\"}"))).isEqualTo("REJECTED");
        assertThat(approve(manager, id).statusCode()).isEqualTo(409);
    }
}
