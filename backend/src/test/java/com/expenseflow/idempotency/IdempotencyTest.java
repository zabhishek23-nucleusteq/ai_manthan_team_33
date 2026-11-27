package com.expenseflow.idempotency;

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
import com.expenseflow.security.JwtTokenService;

/** T-018 / NFR-IDEM through the real HTTP stack (security chain + filter + MVC). */
class IdempotencyTest extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired JwtTokenService tokens;
    @Autowired JdbcTemplate jdbc;

    private String token(long userId) {
        return tokens.issue(userId, "u" + userId + "@expenseflow.test", List.of("EMPLOYEE")).value();
    }

    private static String ref() {
        return "r-" + UUID.randomUUID();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private HttpResponse<String> post(String path, String token, String key, String json) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path)))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json));
        if (key != null) {
            b.header(Idempotent.HEADER, key);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String body(String ref, String note) {
        return "{\"ref\":\"" + ref + "\",\"note\":\"" + note + "\"}";
    }

    @Test
    void missingKey400() throws Exception {
        String ref = ref();
        HttpResponse<String> res = post("/api/test-probe/idempotent", token(7001), null, body(ref, "a"));
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"code\":\"VALIDATION_FAILED\"").contains("Idempotency-Key");
        assertThat(IdempotencyProbeController.executions(ref)).as("handler must not run").isZero();
    }

    @Test
    void tooShortKey400() throws Exception {
        HttpResponse<String> res = post("/api/test-probe/idempotent", token(7001), "1234567", body(ref(), "a"));
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"code\":\"VALIDATION_FAILED\"");
    }

    @Test
    void idempotency_replaySameResponse() throws Exception {
        String ref = ref();
        String key = key();
        String token = token(7002);

        HttpResponse<String> first = post("/api/test-probe/idempotent", token, key, body(ref, "hello"));
        HttpResponse<String> second = post("/api/test-probe/idempotent", token, key, body(ref, "hello"));

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(second.body()).isEqualTo(first.body()).contains("\"execution\":1");
        assertThat(second.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("application/json"));
        assertThat(first.headers().firstValue(Idempotent.REPLAYED_HEADER)).isEmpty();
        assertThat(second.headers().firstValue(Idempotent.REPLAYED_HEADER)).hasValue("true");
        assertThat(IdempotencyProbeController.executions(ref)).as("replay must not run the handler again").isEqualTo(1);
    }

    @Test
    void idempotency_otherBody422() throws Exception {
        String key = key();
        String token = token(7003);
        assertThat(post("/api/test-probe/idempotent", token, key, body(ref(), "one")).statusCode()).isEqualTo(201);

        String otherRef = ref();
        HttpResponse<String> res = post("/api/test-probe/idempotent", token, key, body(otherRef, "two"));
        assertThat(res.statusCode()).isEqualTo(422);
        assertThat(res.body()).contains("\"code\":\"IDEMPOTENCY_KEY_REUSED\"");
        assertThat(IdempotencyProbeController.executions(otherRef)).isZero();
    }

    @Test
    void sameKeyOnAnotherEndpoint_is422() throws Exception {
        String key = key();
        String token = token(7004);
        String json = body(ref(), "x");
        assertThat(post("/api/test-probe/idempotent", token, key, json).statusCode()).isEqualTo(201);
        assertThat(post("/api/test-probe/idempotent-fail-once", token, key, json).statusCode()).isEqualTo(422);
    }

    @Test
    void keysAreScopedPerUser() throws Exception {
        String key = key();
        String ref = ref();
        assertThat(post("/api/test-probe/idempotent", token(7005), key, body(ref, "x")).statusCode()).isEqualTo(201);
        HttpResponse<String> other = post("/api/test-probe/idempotent", token(7006), key, body(ref, "x"));
        assertThat(other.statusCode()).isEqualTo(201);
        assertThat(other.body()).contains("\"execution\":2");
        assertThat(other.headers().firstValue(Idempotent.REPLAYED_HEADER)).isEmpty();
    }

    @Test
    void serverError_isNotStored_retryWithSameKeyRuns() throws Exception {
        String key = key();
        String ref = ref();
        String token = token(7007);
        HttpResponse<String> failed = post("/api/test-probe/idempotent-fail-once", token, key, body(ref, "x"));
        assertThat(failed.statusCode()).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from idempotency_keys where idem_key = ?", Integer.class, key)).isZero();

        HttpResponse<String> retry = post("/api/test-probe/idempotent-fail-once", token, key, body(ref, "x"));
        assertThat(retry.statusCode()).isEqualTo(200);
        assertThat(retry.body()).contains("\"execution\":2");
    }

    @Test
    void inProgressKey_is409() throws Exception {
        String key = key();
        String json = body(ref(), "x");
        // simulate a first request that is still running: same user, key and request hash, no response yet
        HttpResponse<String> done = post("/api/test-probe/idempotent", token(7008), key, json);
        assertThat(done.statusCode()).isEqualTo(201);
        String hash = jdbc.queryForObject("select request_hash from idempotency_keys where user_id = 7008 and idem_key = ?", String.class, key);
        String runningKey = key();
        jdbc.update("insert into idempotency_keys (user_id, idem_key, request_hash) values (7008, ?, ?)", runningKey, hash);

        HttpResponse<String> res = post("/api/test-probe/idempotent", token(7008), runningKey, json);
        assertThat(res.statusCode()).isEqualTo(409);
        assertThat(res.body()).contains("\"code\":\"CONCURRENT_MODIFICATION\"");
    }

    @Test
    void notAnnotatedPost_needsNoKey() throws Exception {
        String ref = ref();
        HttpResponse<String> res = post("/api/test-probe/not-idempotent", token(7009), null, body(ref, "x"));
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(IdempotencyProbeController.executions(ref)).isEqualTo(1);
    }

    @Test
    void noToken_stillUnauthenticatedFirst() throws Exception {
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url("/api/test-probe/idempotent")))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(ref(), "x"))).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isEqualTo(401);
    }
}
