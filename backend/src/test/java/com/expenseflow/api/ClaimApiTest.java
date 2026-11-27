package com.expenseflow.api;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.domain.ApprovalLevel;
import com.expenseflow.domain.User;
import com.expenseflow.repository.RoleRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.JwtTokenService;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** T-016: claims, receipts, submit, comments, departments over real HTTP with real JWTs for real users. */
class ClaimApiTest extends AbstractIntegrationTest {

    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired JdbcTemplate jdbc;
    @Autowired ClaimEventsProbe probe;
    @Autowired JsonMapper json;

    private final HttpClient http = HttpClient.newHttpClient();

    record Who(long id, String token) {
    }

    private Who user(String... roleCodes) {
        User u = new User("Test " + UUID.randomUUID().toString().substring(0, 6), UUID.randomUUID() + "@expenseflow.test", "x");
        for (String r : roleCodes) u.getRoles().add(roles.findById(r).orElseThrow());
        u = users.save(u);
        return new Who(u.getId(), tokens.issue(u.getId(), u.getEmail(), List.of(roleCodes)).value());
    }

    private Who employee() {
        return user("EMPLOYEE");
    }

    private static String uid() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    /** A recent weekday so WEEKEND_SPEND never interferes. */
    private static LocalDate weekday() {
        LocalDate d = LocalDate.now().minusDays(1);
        while (d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY) d = d.minusDays(1);
        return d;
    }

    private HttpResponse<String> send(String method, String path, String token, String body, String... headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)));
        if (token != null) b.header("Authorization", "Bearer " + token);
        if (body != null) b.header("Content-Type", "application/json");
        for (int i = 0; i + 1 < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> r) {
        return json.readTree(r.body());
    }

    private String line(String category, String amount, String vendor, Long receiptId) {
        return "{\"category\":\"" + category + "\",\"amount\":" + amount + ",\"expenseDate\":\"" + weekday() + "\",\"vendor\":\"" + vendor + "\""
                + (receiptId == null ? "" : ",\"receiptId\":" + receiptId) + "}";
    }

    private long createClaim(Who w, String... lines) throws Exception {
        HttpResponse<String> r = send("POST", "/api/claims", w.token(), "{\"title\":\"Test claim " + uid() + "\",\"lines\":[" + String.join(",", lines) + "]}");
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return body(r).get("id").asLong();
    }

    private HttpResponse<String> submit(Who w, long id, String key) throws Exception {
        return send("POST", "/api/claims/" + id + "/submit", w.token(), null, "Idempotency-Key", key);
    }

    private HttpResponse<String> upload(Who w, String fileName, byte[] content) throws Exception {
        String boundary = "----ef" + uid();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(content);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest req = HttpRequest.newBuilder(URI.create(url("/api/receipts")))
                .header("Authorization", "Bearer " + w.token()).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] pdf(String text) {
        return ("%PDF-1.4\n" + text + "\n%%EOF").getBytes(StandardCharsets.US_ASCII);
    }

    private long receipt(Who w) throws Exception {
        HttpResponse<String> r = upload(w, "r-" + uid() + ".pdf", pdf(uid() + uid()));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(201);
        return body(r).get("id").asLong();
    }

    private long audit(long claimId, String action) {
        return jdbc.queryForObject("select count(*) from audit_events where entity_type = 'CLAIM' and entity_id = ? and action = ?",
                Long.class, Long.toString(claimId), action);
    }

    // ------------------------------------------------------------------ auth + row rules
    @Test
    void noToken_401() throws Exception {
        assertThat(send("GET", "/api/claims", null, null).statusCode()).isEqualTo(401);
        assertThat(send("GET", "/api/departments", null, null).statusCode()).isEqualTo(401);
    }

    @Test
    void createDraft_201() throws Exception {
        Who w = employee();
        HttpResponse<String> r = send("POST", "/api/claims", w.token(),
                "{\"title\":\"Client visit Pune\",\"lines\":[" + line("MEAL", "450.50", "Cafe " + uid(), null) + "," + line("CAB", "300", "Ola " + uid(), null) + "]}");
        assertThat(r.statusCode()).isEqualTo(201);
        assertThat(r.headers().firstValue("Location")).hasValueSatisfying(l -> assertThat(l).startsWith("/api/claims/"));
        JsonNode c = body(r);
        assertThat(c.get("status").asText()).isEqualTo("DRAFT");
        assertThat(c.get("code").asText()).matches("EXP-\\d{4}-\\d{5,}");
        assertThat(c.get("total").decimalValue()).isEqualByComparingTo("750.50");
        assertThat(c.get("lines").size()).isEqualTo(2);
        assertThat(c.get("employee").get("id").asLong()).isEqualTo(w.id());
        assertThat(audit(c.get("id").asLong(), "CLAIM_CREATED")).isEqualTo(1);
    }

    @Test
    void otherUsersClaim_404_butAuditorAndHeadCanView() throws Exception {
        Who owner = employee();
        long id = createClaim(owner, line("MEAL", "200", "Cafe " + uid(), null));
        assertThat(send("GET", "/api/claims/" + id, employee().token(), null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/claims/" + id, user("EMPLOYEE", "MANAGER").token(), null).statusCode()).as("manager not assigned").isEqualTo(404);
        assertThat(send("PUT", "/api/claims/" + id, employee().token(), "{\"title\":\"hijack\",\"lines\":[]}").statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/claims/" + id, user("EMPLOYEE", "FINANCE_AUDITOR").token(), null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/claims/" + id, user("EMPLOYEE", "FINANCE_HEAD").token(), null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/claims", user("SUPER_ADMIN").token(), null).statusCode()).as("admin-only users file no claims").isEqualTo(403);
    }

    @Test
    void listShowsOnlyMyClaims_withStatusFilter() throws Exception {
        Who me = employee();
        createClaim(me, line("MEAL", "100", "Cafe " + uid(), null));
        createClaim(me, line("MEAL", "110", "Cafe " + uid(), null));
        createClaim(employee(), line("MEAL", "120", "Cafe " + uid(), null));
        JsonNode page = body(send("GET", "/api/claims?size=10", me.token(), null));
        assertThat(page.get("total").asLong()).isEqualTo(2);
        assertThat(page.get("items").get(0).get("lineCount").asInt()).isEqualTo(1);
        assertThat(body(send("GET", "/api/claims?status=PAID", me.token(), null)).get("total").asLong()).isZero();
        assertThat(send("GET", "/api/claims?status=NOPE", me.token(), null).statusCode()).isEqualTo(400);
    }

    @Test
    void editDraft_200_editSubmitted_409_deleteRules() throws Exception {
        Who w = employee();
        long id = createClaim(w, line("MEAL", "200", "Cafe " + uid(), null));
        HttpResponse<String> edited = send("PUT", "/api/claims/" + id, w.token(),
                "{\"title\":\"Edited title\",\"lines\":[" + line("CAB", "250", "Uber " + uid(), null) + "," + line("MEAL", "150", "Cafe " + uid(), null) + "]}");
        assertThat(edited.statusCode()).as(edited.body()).isEqualTo(200);
        assertThat(body(edited).get("lines").size()).isEqualTo(2);
        assertThat(body(edited).get("total").decimalValue()).isEqualByComparingTo("400");

        assertThat(submit(w, id, "k-" + uid()).statusCode()).isEqualTo(200);
        HttpResponse<String> late = send("PUT", "/api/claims/" + id, w.token(), "{\"title\":\"Too late\",\"lines\":[]}");
        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(late.body()).contains("ILLEGAL_STATE_TRANSITION");
        assertThat(send("DELETE", "/api/claims/" + id, w.token(), null).statusCode()).isEqualTo(409);

        long draft = createClaim(w, line("MEAL", "90", "Cafe " + uid(), null));
        assertThat(send("DELETE", "/api/claims/" + draft, w.token(), null).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/api/claims/" + draft, w.token(), null).statusCode()).isEqualTo(404);
    }

    @Test
    void validation_hotelNeedsNightsAndCity_noFutureDates_onlyOwnReceipts() throws Exception {
        Who w = employee();
        HttpResponse<String> hotel = send("POST", "/api/claims", w.token(), "{\"title\":\"Hotel stay\",\"lines\":[" + line("HOTEL", "3000", "Taj", null) + "]}");
        assertThat(hotel.statusCode()).isEqualTo(400);
        assertThat(hotel.body()).contains("lines[0].nights").contains("lines[0].city");

        String future = "{\"category\":\"MEAL\",\"amount\":100,\"expenseDate\":\"" + LocalDate.now().plusDays(3) + "\",\"vendor\":\"X\"}";
        assertThat(send("POST", "/api/claims", w.token(), "{\"title\":\"Future\",\"lines\":[" + future + "]}").statusCode()).isEqualTo(400);

        long othersReceipt = receipt(employee());
        HttpResponse<String> foreign = send("POST", "/api/claims", w.token(), "{\"title\":\"Foreign receipt\",\"lines\":[" + line("MEAL", "700", "Cafe", othersReceipt) + "]}");
        assertThat(foreign.statusCode()).isEqualTo(400);
        assertThat(foreign.body()).contains("receiptId");
    }

    // ------------------------------------------------------------------ receipts
    @Test
    void receipt_upload_sniffed_sha256_andRowRule() throws Exception {
        Who w = employee();
        byte[] content = pdf("taxi " + uid());
        HttpResponse<String> r = upload(w, "taxi.pdf", content);
        assertThat(r.statusCode()).isEqualTo(201);
        JsonNode rec = body(r);
        assertThat(rec.get("contentType").asText()).isEqualTo("application/pdf");
        assertThat(rec.get("sha256").asText()).isEqualTo(com.expenseflow.service.ReceiptServiceSha.sha(content));
        long id = rec.get("id").asLong();

        HttpResponse<String> mine = send("GET", "/api/receipts/" + id + "/content", w.token(), null);
        assertThat(mine.statusCode()).isEqualTo(200);
        assertThat(mine.headers().firstValue("Content-Type")).hasValue("application/pdf");
        assertThat(send("GET", "/api/receipts/" + id + "/content", employee().token(), null).statusCode()).isEqualTo(404);
        assertThat(send("GET", "/api/receipts/" + id + "/content", user("EMPLOYEE", "FINANCE_AUDITOR").token(), null).statusCode()).isEqualTo(200);
    }

    @Test
    void receipt_tooLarge_413_andWrongType_415() throws Exception {
        Who w = employee();
        byte[] big = new byte[6 * 1024 * 1024];
        System.arraycopy(pdf("x"), 0, big, 0, 8);
        HttpResponse<String> large = upload(w, "big.pdf", big);
        assertThat(large.statusCode()).isEqualTo(413);
        assertThat(large.body()).contains("RECEIPT_TOO_LARGE");

        HttpResponse<String> exe = upload(w, "invoice.pdf", new byte[] {'M', 'Z', 0x00, 0x01, 0x02});
        assertThat(exe.statusCode()).as("renamed .exe is detected by its bytes").isEqualTo(415);
        assertThat(exe.body()).contains("UNSUPPORTED_FILE_TYPE");

        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};
        HttpResponse<String> ok = upload(w, "photo.bin", png);
        assertThat(ok.statusCode()).isEqualTo(201);
        assertThat(body(ok).get("contentType").asText()).isEqualTo("image/png");
    }

    // ------------------------------------------------------------------ submit
    @Test
    void submit_autoApproved_toApproved_andAudited() throws Exception {
        Who w = employee();
        long id = createClaim(w, line("MEAL", "320", "Cafe " + uid(), null), line("CAB", "180", "Uber " + uid(), null));
        HttpResponse<String> r = submit(w, id, "k-" + uid());
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        JsonNode c = body(r);
        assertThat(c.get("status").asText()).isEqualTo("APPROVED");
        assertThat(c.get("policyResult").get("outcome").asText()).isEqualTo("AUTO_APPROVED");
        assertThat(c.get("sampleAudit").asText()).isIn("NONE", "PENDING");
        assertThat(c.get("submittedAt").isNull()).isFalse();
        assertThat(audit(id, "CLAIM_SUBMITTED")).isEqualTo(1);
        assertThat(audit(id, "CLAIM_AUTO_APPROVED")).isEqualTo(1);
    }

    @Test
    void submit_missingReceipt_autoRejectedWithReason() throws Exception {
        Who w = employee();
        long id = createClaim(w, line("OTHER", "800", "Croma " + uid(), null));
        JsonNode c = body(submit(w, id, "k-" + uid()));
        assertThat(c.get("status").asText()).isEqualTo("AUTO_REJECTED");
        assertThat(c.get("lines").get(0).get("violations").get(0).get("code").asText()).isEqualTo("RECEIPT_REQUIRED");
        assertThat(c.get("policyResult").get("reasons").get(0).asText()).contains("Receipt required above 500");
        assertThat(audit(id, "CLAIM_AUTO_REJECTED")).isEqualTo(1);
    }

    @Test
    void submit_routed_pendingManager_publishesRoutedEventForApprovalService() throws Exception {
        Who w = employee();
        long rid = receipt(w);
        long id = createClaim(w, line("TRAVEL", "30000", "IndiGo " + uid(), rid));
        JsonNode c = body(submit(w, id, "k-" + uid()));
        assertThat(c.get("status").asText()).isEqualTo("PENDING_MANAGER");
        assertThat(c.get("policyResult").get("requiredLevels").toString()).contains("MANAGER").contains("AUDITOR");
        var e = probe.routed.stream().filter(x -> x.claimId() == id).findFirst().orElseThrow();
        assertThat(e.requiredLevels()).containsExactly(ApprovalLevel.MANAGER, ApprovalLevel.AUDITOR);
        assertThat(e.round()).isEqualTo(1);
        assertThat(e.employeeId()).isEqualTo(w.id());
        assertThat(audit(id, "CLAIM_ROUTED")).isEqualTo(1);
    }

    @Test
    void submit_sameKeyTwice_sameResult() throws Exception {
        Who w = employee();
        long id = createClaim(w, line("MEAL", "250", "Cafe " + uid(), null));
        String key = "same-" + uid();
        HttpResponse<String> first = submit(w, id, key);
        HttpResponse<String> second = submit(w, id, key);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(second.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(audit(id, "CLAIM_SUBMITTED")).as("ran once").isEqualTo(1);

        HttpResponse<String> otherKey = submit(w, id, "other-" + uid());
        assertThat(otherKey.statusCode()).as("already submitted").isEqualTo(409);
    }

    @Test
    void submit_withoutIdempotencyKey_400_andEmptyClaim_422() throws Exception {
        Who w = employee();
        long id = createClaim(w, line("MEAL", "250", "Cafe " + uid(), null));
        assertThat(send("POST", "/api/claims/" + id + "/submit", w.token(), null).statusCode()).isEqualTo(400);

        long empty = createClaim(w);
        HttpResponse<String> r = submit(w, empty, "k-" + uid());
        assertThat(r.statusCode()).isEqualTo(422);
        assertThat(r.body()).contains("CLAIM_EMPTY");
    }

    @Test
    void submit_concurrent_one409() throws Exception {
        Who w = employee();
        long id = createClaim(w, line("MEAL", "260", "Cafe " + uid(), null));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String key = "race-" + i + "-" + uid();
            Callable<Integer> call = () -> { go.await(); return submit(w, id, key).statusCode(); };
            results.add(pool.submit(call));
        }
        go.countDown();
        List<Integer> codes = new ArrayList<>();
        for (Future<Integer> f : results) codes.add(f.get());
        pool.shutdown();
        assertThat(codes).containsExactlyInAnyOrder(200, 409);
        assertThat(audit(id, "CLAIM_AUTO_APPROVED")).as("decided exactly once").isEqualTo(1);
    }

    // ------------------------------------------------------------------ comments + departments
    @Test
    void comments_ownerAndFinanceOnly() throws Exception {
        Who w = employee();
        long id = createClaim(w, line("MEAL", "140", "Cafe " + uid(), null));
        HttpResponse<String> posted = send("POST", "/api/claims/" + id + "/comments", w.token(), "{\"text\":\"Receipt is in the drive\"}");
        assertThat(posted.statusCode()).isEqualTo(201);
        assertThat(body(posted).get("author").get("id").asLong()).isEqualTo(w.id());
        assertThat(body(send("GET", "/api/claims/" + id + "/comments", user("EMPLOYEE", "FINANCE_HEAD").token(), null)).size()).isEqualTo(1);
        assertThat(send("GET", "/api/claims/" + id + "/comments", employee().token(), null).statusCode()).isEqualTo(404);
        assertThat(send("POST", "/api/claims/" + id + "/comments", w.token(), "{\"text\":\"\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void departments_forEverySignedInRole() throws Exception {
        assertThat(send("GET", "/api/departments", user("SUPER_ADMIN").token(), null).statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/departments", employee().token(), null).statusCode()).isEqualTo(200);
        assertThat(Set.of(200)).contains(send("GET", "/api/departments", user("EMPLOYEE", "MANAGER").token(), null).statusCode());
    }
}
