package com.aimanthan.expenseflow.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.aimanthan.expenseflow.AbstractIntegrationTest;

class JwtSecurityTest extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    JwtTokenService tokens;

    @Autowired
    PasswordEncoder passwordEncoder;

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void noToken_is401Json() throws Exception {
        HttpResponse<String> res = get("/api/test-probe/authenticated", null);
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v).contains("application/json"));
        assertThat(res.body()).contains("\"code\":\"UNAUTHENTICATED\"");
    }

    @Test
    void validToken_isAccepted_andCarriesSubAndRoles() throws Exception {
        String token = tokens.issue(42, "asha@expenseflow.test", List.of("EMPLOYEE")).value();
        HttpResponse<String> res = get("/api/test-probe/authenticated", token);
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"sub\":\"42\"").contains("EMPLOYEE");
    }

    @Test
    void defaultTtlIsEightHours() {
        assertThat(tokens.ttlSeconds()).isEqualTo(8 * 3600);
        assertThat(tokens.issue(1, "a@b.test", List.of("EMPLOYEE")).expiresInSeconds()).isEqualTo(28800);
    }

    @Test
    void expiredToken_is401() throws Exception {
        // issued 9 h ago with an 8 h lifetime -> expired 1 h ago (well past the 60 s clock skew)
        String token = tokens.issue(42, "a@b.test", List.of("EMPLOYEE"),
                Instant.now().minus(Duration.ofHours(9)), Duration.ofHours(8)).value();
        HttpResponse<String> res = get("/api/test-probe/authenticated", token);
        assertThat(res.statusCode()).isEqualTo(401);
        assertThat(res.body()).contains("\"code\":\"UNAUTHENTICATED\"");
    }

    @Test
    void tamperedOrGarbageToken_is401() throws Exception {
        String token = tokens.issue(42, "a@b.test", List.of("EMPLOYEE")).value();
        String[] parts = token.split("\\.");
        // swap the payload for one that claims SUPER_ADMIN, keep the old signature
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                "{\"sub\":\"42\",\"roles\":[\"SUPER_ADMIN\"],\"exp\":4102444800}".getBytes());
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];
        assertThat(get("/api/test-probe/super-admin", forged).statusCode()).isEqualTo(401);
        assertThat(get("/api/test-probe/authenticated", "not-a-jwt").statusCode()).isEqualTo(401);
    }

    @Test
    void wrongRole_is403Json_rightRole_is200() throws Exception {
        String employee = tokens.issue(42, "a@b.test", List.of("EMPLOYEE")).value();
        HttpResponse<String> denied = get("/api/test-probe/super-admin", employee);
        assertThat(denied.statusCode()).isEqualTo(403);
        assertThat(denied.body()).contains("\"code\":\"FORBIDDEN\"");

        String admin = tokens.issue(1, "admin@b.test", List.of("SUPER_ADMIN")).value();
        assertThat(get("/api/test-probe/super-admin", admin).statusCode()).isEqualTo(200);
    }

    @Test
    void passwordEncoderIsBcrypt() {
        String hash = passwordEncoder.encode("Demo@123");
        assertThat(hash).startsWith("$2");
        assertThat(passwordEncoder.matches("Demo@123", hash)).isTrue();
        assertThat(passwordEncoder.matches("wrong", hash)).isFalse();
    }
}
