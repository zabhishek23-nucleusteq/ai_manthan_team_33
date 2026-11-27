package com.expenseflow.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.repository.UserRepository;

/** FR-1 end to end over HTTP with a real PostgreSQL. Each test uses its own random email. */
class AuthApiTest extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    UserRepository users;

    private HttpResponse<String> post(String path, String json) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String email() {
        return "user-" + UUID.randomUUID().toString().substring(0, 8) + "@expenseflow.test";
    }

    private HttpResponse<String> signup(String email, String password) throws Exception {
        return post("/api/auth/signup",
                "{\"name\":\"Asha Rao\",\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private HttpResponse<String> login(String email, String password) throws Exception {
        return post("/api/auth/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private static String token(HttpResponse<String> loginRes) {
        Matcher m = Pattern.compile("\"accessToken\":\"([^\"]+)\"").matcher(loginRes.body());
        assertThat(m.find()).as("accessToken in %s", loginRes.body()).isTrue();
        return m.group(1);
    }

    @Test
    void signup_201_asEmployeeOnly_passwordNotReturned() throws Exception {
        String email = email();
        HttpResponse<String> res = signup(email, "Demo@1234");
        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(res.body()).contains("\"email\":\"" + email + "\"").contains("\"roles\":[\"EMPLOYEE\"]")
                .contains("\"enabled\":true").doesNotContain("password");
        assertThat(users.findByEmailIgnoreCase(email)).hasValueSatisfying(u -> {
            assertThat(u.getPasswordHash()).startsWith("$2").isNotEqualTo("Demo@1234");
            assertThat(u.roleCodes()).containsExactly("EMPLOYEE");
        });
    }

    @Test
    void signup_ignoresAttemptToSelfAssignRoles() throws Exception {
        String email = email();
        HttpResponse<String> res = post("/api/auth/signup", "{\"name\":\"Eve\",\"email\":\"" + email
                + "\",\"password\":\"Demo@1234\",\"roles\":[\"SUPER_ADMIN\"]}");
        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(res.body()).contains("\"roles\":[\"EMPLOYEE\"]").doesNotContain("SUPER_ADMIN");
    }

    @Test
    void signup_duplicateEmail_409_caseInsensitive() throws Exception {
        String email = email();
        assertThat(signup(email, "Demo@1234").statusCode()).isEqualTo(201);
        HttpResponse<String> dup = signup(email.toUpperCase(), "Other@1234");
        assertThat(dup.statusCode()).isEqualTo(409);
        assertThat(dup.body()).contains("\"code\":\"EMAIL_TAKEN\"");
    }

    @Test
    void signup_parallelSameEmail_exactlyOneWins() throws Exception {
        String email = email();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                calls.add(() -> signup(email, "Demo@1234").statusCode());
            }
            List<Integer> codes = new ArrayList<>();
            for (Future<Integer> f : pool.invokeAll(calls)) {
                codes.add(f.get());
            }
            assertThat(codes).containsOnly(201, 409);
            assertThat(codes).filteredOn(c -> c == 201).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void signup_validation_400() throws Exception {
        HttpResponse<String> shortPw = signup(email(), "short7!");
        assertThat(shortPw.statusCode()).isEqualTo(400);
        assertThat(shortPw.body()).contains("VALIDATION_FAILED").contains("password");
        assertThat(signup("not-an-email", "Demo@1234").statusCode()).isEqualTo(400);
        assertThat(post("/api/auth/signup", "{\"name\":\"A\",\"email\":\"" + email() + "\",\"password\":\"Demo@1234\"}")
                .statusCode()).isEqualTo(400);
        assertThat(post("/api/auth/signup", "{\"name\":\"Ab\",\"email\":\"" + email()
                + "\",\"password\":\"Demo@1234\",\"departmentId\":999999}").statusCode()).isEqualTo(400);
    }

    @Test
    void login_ok_returnsBearerToken_andMeWorks() throws Exception {
        String email = email();
        signup(email, "Demo@1234");
        HttpResponse<String> res = login(email.toUpperCase(), "Demo@1234");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"tokenType\":\"Bearer\"").contains("\"expiresIn\":28800")
                .contains("\"roles\":[\"EMPLOYEE\"]");

        HttpResponse<String> me = get("/api/auth/me", token(res));
        assertThat(me.statusCode()).isEqualTo(200);
        assertThat(me.body()).contains("\"email\":\"" + email + "\"").contains("EMPLOYEE");
    }

    @Test
    void login_wrongPassword_and_unknownEmail_sameGeneric401() throws Exception {
        String email = email();
        signup(email, "Demo@1234");
        HttpResponse<String> wrongPw = login(email, "Wrong@1234");
        HttpResponse<String> unknown = login(email(), "Demo@1234");
        assertThat(wrongPw.statusCode()).isEqualTo(401);
        assertThat(unknown.statusCode()).isEqualTo(401);
        assertThat(wrongPw.body()).contains("\"code\":\"INVALID_CREDENTIALS\"");
        assertThat(unknown.body()).isEqualTo(wrongPw.body());
    }

    @Test
    void login_disabledUser_401_andExistingTokenStopsWorkingOnMe() throws Exception {
        String email = email();
        signup(email, "Demo@1234");
        String token = token(login(email, "Demo@1234"));
        users.findByEmailIgnoreCase(email).ifPresent(u -> {
            u.setEnabled(false);
            users.save(u);
        });
        HttpResponse<String> res = login(email, "Demo@1234");
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("ACCOUNT_DISABLED");
        assertThat(get("/api/auth/me", token).statusCode()).isEqualTo(401);
    }

    @Test
    void login_bruteForce_throttledWith429_evenForCorrectPassword() throws Exception {
        String email = email();
        signup(email, "Demo@1234");
        for (int i = 0; i < 5; i++) {
            HttpResponse<String> res = login(email, "Wrong@" + i + "234");
            assertThat(res.statusCode()).as("attempt %d", i + 1).isEqualTo(401);
            assertThat(res.body()).contains("INVALID_CREDENTIALS");
        }
        HttpResponse<String> blocked = login(email, "Demo@1234");
        assertThat(blocked.statusCode()).isEqualTo(429);
        assertThat(blocked.body()).contains("\"code\":\"TOO_MANY_REQUESTS\"");
        assertThat(blocked.headers().firstValue("Retry-After")).hasValueSatisfying(v ->
                assertThat(Long.parseLong(v)).isBetween(1L, 61L));
        // other accounts are not affected
        String other = email();
        signup(other, "Demo@1234");
        assertThat(login(other, "Demo@1234").statusCode()).isEqualTo(200);
    }

    @Test
    void me_withoutToken_401() throws Exception {
        HttpResponse<String> res = get("/api/auth/me", null);
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("\"code\":\"UNAUTHENTICATED\"");
    }

    @Test
    void employeeToken_onSuperAdminEndpoint_403() throws Exception {
        String email = email();
        signup(email, "Demo@1234");
        HttpResponse<String> res = get("/api/test-probe/super-admin", token(login(email, "Demo@1234")));
        assertThat(res.statusCode()).isEqualTo(403);
        assertThat(res.body()).contains("\"code\":\"FORBIDDEN\"");
    }
}
