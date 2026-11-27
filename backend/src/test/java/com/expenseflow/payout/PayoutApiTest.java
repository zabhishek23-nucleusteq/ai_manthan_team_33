package com.expenseflow.payout;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.domain.Claim;
import com.expenseflow.domain.ClaimLine;
import com.expenseflow.domain.ClaimStatus;
import com.expenseflow.domain.ExpenseCategory;
import com.expenseflow.domain.SampleAudit;
import com.expenseflow.domain.User;
import com.expenseflow.repository.ClaimRepository;
import com.expenseflow.repository.RoleRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.JwtTokenService;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** T-017 payouts against a fake bank that behaves like services/mock-bank (stores results per Idempotency-Key). */
class PayoutApiTest extends AbstractIntegrationTest {

    // ------------------------------------------------------------------ fake bank
    enum Mode { OK, ERROR_500, SLOW }

    static final HttpServer BANK;
    static volatile Mode mode = Mode.OK;
    static final Set<String> rejectCodes = ConcurrentHashMap.newKeySet();
    static final Map<String, String> storedByKey = new ConcurrentHashMap<>();
    static final Map<String, AtomicInteger> processedByKey = new ConcurrentHashMap<>();

    static {
        try {
            BANK = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        BANK.setExecutor(Executors.newCachedThreadPool());
        BANK.createContext("/bank/payout-files", ex -> {
            String key = ex.getRequestHeaders().getFirst("Idempotency-Key");
            String csv = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String stored = storedByKey.get(key);
            int status;
            String body;
            if (stored != null) {                                   // replay: never processed twice
                status = 200;
                body = stored;
            } else if (mode == Mode.ERROR_500) {
                status = 500;
                body = "{\"code\":\"INJECTED_FAULT\"}";
            } else {
                body = process(key, csv);                           // processed (and stored) even if the caller times out
                if (mode == Mode.SLOW) sleep(2500);
                status = 201;
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, out.length);
            try (OutputStream os = ex.getResponseBody()) {
                os.write(out);
            } catch (IOException ignored) {
                // client gave up (timeout)
            }
        });
        BANK.start();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String process(String key, String csv) {
        processedByKey.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        List<String> errors = new ArrayList<>();
        int accepted = 0, rejected = 0, lineNo = 0;
        BigDecimal amount = BigDecimal.ZERO;
        String batchId = "";
        for (String line : csv.split("\n")) {
            lineNo++;
            String[] f = line.split(",");
            if (f[0].equals("D")) {
                batchId = f[1];
                String narration = f[9];
                if (rejectCodes.contains(narration)) {
                    rejected++;
                    errors.add("{\"line\":" + lineNo + ",\"reference\":\"" + narration + "\",\"reason\":\"INVALID_ACCOUNT\"}");
                } else {
                    accepted++;
                    amount = amount.add(new BigDecimal(f[7]));
                }
            }
        }
        String status = accepted == 0 ? "REJECTED" : rejected == 0 ? "ACCEPTED" : "PARTIALLY_ACCEPTED";
        String body = "{\"fileId\":\"F-" + key + "\",\"batchId\":\"" + batchId + "\",\"valueDate\":\"" + LocalDate.now() + "\",\"accepted\":" + accepted
                + ",\"rejected\":" + rejected + ",\"acceptedAmount\":" + amount + ",\"status\":\"" + status + "\",\"errors\":[" + String.join(",", errors)
                + "],\"receivedAt\":\"" + Instant.now() + "\"}";
        storedByKey.put(key, body);
        return body;
    }

    @DynamicPropertySource
    static void bankProps(DynamicPropertyRegistry r) {
        r.add("app.bank.url", () -> "http://127.0.0.1:" + BANK.getAddress().getPort());
        r.add("app.bank.timeout", () -> "PT1S");
    }

    @AfterAll
    static void stopBank() {
        BANK.stop(0);
    }

    // ------------------------------------------------------------------ helpers
    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired ClaimRepository claims;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;
    @Autowired MeterRegistry meters;
    @Autowired TransactionTemplate tx;

    private final HttpClient http = HttpClient.newHttpClient();
    private String head;

    @BeforeEach
    void reset() {
        mode = Mode.OK;
        rejectCodes.clear();
        head = token("EMPLOYEE", "FINANCE_HEAD");
        // start every test from "nothing payable" so counts are exact
        jdbc.update("update claims set status = 'REJECTED' where status = 'APPROVED'");
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private User saveUser(String... roleCodes) {
        User u = new User("Payee " + uid(), uid() + "@expenseflow.test", "x");
        u.setEmployeeCode("EMP" + uid());
        u.setBankAccountNo("50100" + (1000000 + (int) (Math.random() * 8999999)));
        u.setIfsc("HDFC0" + (100000 + (int) (Math.random() * 899999)));
        for (String r : roleCodes) u.getRoles().add(roles.findById(r).orElseThrow());
        return users.save(u);
    }

    private String token(String... roleCodes) {
        User u = saveUser(roleCodes);
        return tokens.issue(u.getId(), u.getEmail(), List.of(roleCodes)).value();
    }

    private Claim approvedClaim(String amount, SampleAudit sample) {
        return tx.execute(s -> {
            User u = saveUser("EMPLOYEE");
            Claim c = new Claim("EXP-T-" + uid(), u, "Payout test");
            c.addLine(new ClaimLine(ExpenseCategory.TRAVEL, LocalDate.now().minusDays(3), new BigDecimal(amount), "IndiGo " + uid()));
            c.setStatus(ClaimStatus.APPROVED);
            c.setSubmittedAt(Instant.now().minusSeconds(3600));
            c.setDecidedAt(Instant.now().minusSeconds(600));
            c.setSampleAudit(sample);
            return claims.saveAndFlush(c);
        });
    }

    private HttpResponse<String> call(String method, String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).method(method, HttpRequest.BodyPublishers.noBody());
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (method.equals("POST")) b.header("Idempotency-Key", "k-" + uid());
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> r) {
        return json.readTree(r.body());
    }

    private long createBatch() throws Exception {
        HttpResponse<String> r = call("POST", "/api/payout-batches", head);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return body(r).get("id").asLong();
    }

    private String claimStatus(long id) {
        return jdbc.queryForObject("select status from claims where id = ?", String.class, id);
    }

    private long paidAudits(long claimId) {
        return jdbc.queryForObject("select count(*) from audit_events where action = 'CLAIM_PAID' and entity_id = ?", Long.class, Long.toString(claimId));
    }

    // ------------------------------------------------------------------ tests
    @Test
    void roles_auditorReadsHeadWrites_othersDenied() throws Exception {
        String auditor = token("EMPLOYEE", "FINANCE_AUDITOR");
        assertThat(call("GET", "/api/payout-batches", auditor).statusCode()).isEqualTo(200);
        assertThat(call("POST", "/api/payout-batches", auditor).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/payout-batches", token("EMPLOYEE")).statusCode()).isEqualTo(403);
        assertThat(call("GET", "/api/payout-batches", null).statusCode()).isEqualTo(401);
        // non-head without Idempotency-Key still gets 403, not the filter's 400
        HttpRequest noKey = HttpRequest.newBuilder(URI.create(url("/api/payout-batches"))).header("Authorization", "Bearer " + auditor)
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        assertThat(http.send(noKey, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
    }

    @Test
    void createBatch_takesPayableClaims_toInPayout_then422WhenNothingLeft() throws Exception {
        Claim a = approvedClaim("1250.00", SampleAudit.NONE);
        Claim b = approvedClaim("800.50", SampleAudit.OK);
        long id = createBatch();
        JsonNode batch = body(call("GET", "/api/payout-batches/" + id, head));
        assertThat(batch.get("status").asText()).isEqualTo("CREATED");
        assertThat(batch.get("itemCount").asInt()).isEqualTo(2);
        assertThat(batch.get("total").decimalValue()).isEqualByComparingTo("2050.50");
        assertThat(batch.get("code").asText()).matches("PB-\\d{4,}");
        assertThat(claimStatus(a.getId())).isEqualTo("IN_PAYOUT");
        assertThat(claimStatus(b.getId())).isEqualTo("IN_PAYOUT");

        HttpResponse<String> again = call("POST", "/api/payout-batches", head);
        assertThat(again.statusCode()).isEqualTo(422);
        assertThat(again.body()).contains("NO_ELIGIBLE_CLAIMS");
    }

    @Test
    void payable_excludesPendingSampleAudit() throws Exception {
        Claim pending = approvedClaim("700.00", SampleAudit.PENDING);
        Claim exception = approvedClaim("710.00", SampleAudit.EXCEPTION);
        Claim ok = approvedClaim("720.00", SampleAudit.NONE);
        long id = createBatch();
        JsonNode batch = body(call("GET", "/api/payout-batches/" + id, head));
        List<Long> ids = new ArrayList<>();
        batch.get("items").forEach(i -> ids.add(i.get("claimId").asLong()));
        assertThat(ids).containsExactly(ok.getId());
        assertThat(claimStatus(pending.getId())).isEqualTo("APPROVED");
        assertThat(claimStatus(exception.getId())).isEqualTo("APPROVED");
    }

    @Test
    void createBatch_concurrent_noClaimTwice() throws Exception {
        Set<Long> mine = new HashSet<>();
        for (int i = 0; i < 8; i++) mine.add(approvedClaim(Integer.toString(100 + i), SampleAudit.NONE).getId());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<HttpResponse<String>>> f = new ArrayList<>();
        for (int i = 0; i < 2; i++) f.add(pool.submit(() -> { go.await(); return call("POST", "/api/payout-batches", head); }));
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<HttpResponse<String>> x : f) codes.add(x.get().statusCode());
        pool.shutdown();
        assertThat(codes).allMatch(c -> c == 201 || c == 422).contains(201);

        List<Long> inItems = jdbc.queryForList("select claim_id from payout_items where claim_id in (" + String.join(",", mine.stream().map(String::valueOf).toList()) + ")", Long.class);
        assertThat(inItems).as("every claim exactly once").hasSize(8).doesNotHaveDuplicates();
        assertThat(new HashSet<>(inItems)).isEqualTo(mine);
    }

    @Test
    void csv_format_matchesBR17() throws Exception {
        Claim a = approvedClaim("1250.00", SampleAudit.NONE);
        Claim b = approvedClaim("99.5", SampleAudit.NONE);
        jdbc.update("update users set name = 'Rao, Priya' where id = (select employee_id from claims where id = ?)", b.getId());
        long id = createBatch();
        HttpResponse<String> r = call("GET", "/api/payout-batches/" + id + "/export.csv", head);
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("text/csv");
        String code = body(call("GET", "/api/payout-batches/" + id, head)).get("code").asText();
        String date = LocalDate.now(java.time.ZoneOffset.UTC).toString();
        List<String> lines = r.body().lines().toList();

        assertThat(lines.get(0)).isEqualTo("RECORD_TYPE,BATCH_ID,VALUE_DATE,EMPLOYEE_CODE,BENEFICIARY_NAME,ACCOUNT_NO,IFSC,AMOUNT,CURRENCY,NARRATION");
        assertThat(lines).hasSize(4);
        Map<String, Object> ua = jdbc.queryForMap("select u.employee_code, u.name, u.bank_account_no, u.ifsc from users u join claims c on c.employee_id = u.id where c.id = ?", a.getId());
        assertThat(lines.get(1)).isEqualTo(String.join(",", "D", code, date, (String) ua.get("employee_code"), (String) ua.get("name"),
                (String) ua.get("bank_account_no"), (String) ua.get("ifsc"), "1250.00", "INR", a.getCode()));
        assertThat(lines.get(2)).as("name with a comma is quoted, amount has 2 decimals").contains(",\"Rao, Priya\",").contains(",99.50,INR," + b.getCode());
        assertThat(lines.get(3)).isEqualTo(String.join(",", "T", code, date, "2", "1349.50"));
    }

    @Test
    void submit_accepted_batchAndClaimsPaid_audited_metric() throws Exception {
        Claim a = approvedClaim("500.00", SampleAudit.NONE);
        long id = createBatch();
        double before = meters.counter("expenseflow_bank_submit_total", "result", "accepted").count();
        HttpResponse<String> r = call("POST", "/api/payout-batches/" + id + "/submit", head);
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        assertThat(body(r).get("status").asText()).isEqualTo("PAID");
        assertThat(body(r).get("bankReference").asText()).isEqualTo("F-payout-batch-" + id);
        assertThat(claimStatus(a.getId())).isEqualTo("PAID");
        assertThat(jdbc.queryForObject("select paid_at is not null from claims where id = ?", Boolean.class, a.getId())).isTrue();
        assertThat(paidAudits(a.getId())).isEqualTo(1);
        assertThat(meters.counter("expenseflow_bank_submit_total", "result", "accepted").count()).isEqualTo(before + 1);
        assertThat(call("POST", "/api/payout-batches/" + id + "/submit", head).statusCode()).as("already paid").isEqualTo(409);
    }

    @Test
    void submit_bank500_failed() throws Exception {
        Claim a = approvedClaim("640.00", SampleAudit.NONE);
        long id = createBatch();
        mode = Mode.ERROR_500;
        HttpResponse<String> r = call("POST", "/api/payout-batches/" + id + "/submit", head);
        assertThat(r.statusCode()).isEqualTo(502);
        assertThat(r.body()).contains("BANK_UNAVAILABLE");
        JsonNode batch = body(call("GET", "/api/payout-batches/" + id, head));
        assertThat(batch.get("status").asText()).isEqualTo("SUBMIT_FAILED");
        assertThat(batch.get("lastError").asText()).contains("500");
        assertThat(claimStatus(a.getId())).isEqualTo("IN_PAYOUT");
        assertThat(meters.counter("expenseflow_bank_submit_total", "result", "http_5xx").count()).isPositive();
    }

    @Test
    void submit_timeout_retry_paidOnce() throws Exception {
        Claim a = approvedClaim("910.00", SampleAudit.NONE);
        long id = createBatch();
        mode = Mode.SLOW;                                    // bank processes the file but answers after our 1 s timeout
        HttpResponse<String> first = call("POST", "/api/payout-batches/" + id + "/submit", head);
        assertThat(first.statusCode()).isEqualTo(502);
        assertThat(body(call("GET", "/api/payout-batches/" + id, head)).get("status").asText()).isEqualTo("SUBMIT_FAILED");
        assertThat(claimStatus(a.getId())).isEqualTo("IN_PAYOUT");

        mode = Mode.OK;
        HttpResponse<String> retry = call("POST", "/api/payout-batches/" + id + "/submit", head);
        assertThat(retry.statusCode()).as(retry.body()).isEqualTo(200);
        assertThat(body(retry).get("status").asText()).isEqualTo("PAID");
        assertThat(processedByKey.get("payout-batch-" + id).get()).as("bank processed the file once").isEqualTo(1);
        assertThat(paidAudits(a.getId())).as("claim paid once").isEqualTo(1);
    }

    @Test
    void submit_partiallyAccepted_rejectedClaimBackToApproved() throws Exception {
        Claim good = approvedClaim("300.00", SampleAudit.NONE);
        Claim bad = approvedClaim("400.00", SampleAudit.NONE);
        long id = createBatch();
        rejectCodes.add(bad.getCode());
        JsonNode r = body(call("POST", "/api/payout-batches/" + id + "/submit", head));
        assertThat(r.get("status").asText()).isEqualTo("PAID");
        assertThat(r.get("itemCount").asInt()).isEqualTo(1);
        assertThat(r.get("lastError").asText()).contains(bad.getCode());
        assertThat(claimStatus(good.getId())).isEqualTo("PAID");
        assertThat(claimStatus(bad.getId())).as("payable again in a later batch").isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("select count(*) from payout_items where claim_id = ?", Long.class, bad.getId())).isZero();
    }
}
