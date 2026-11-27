package com.expenseflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.domain.User;
import com.expenseflow.repository.RoleRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.JwtTokenService;

/**
 * Regressions found by L6's RBAC matrix (e2e-api in CI): a role without access must get 403 before any body
 * validation, and malformed requests from allowed roles must be 4xx, never 500.
 */
class UrlRoleGateTest extends AbstractIntegrationTest {

    @Autowired JwtTokenService tokens;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;

    private final HttpClient http = HttpClient.newHttpClient();

    private String token(String... roleCodes) {
        User u = new User("Gate " + UUID.randomUUID().toString().substring(0, 6), UUID.randomUUID() + "@expenseflow.test", "x");
        for (String r : roleCodes) u.getRoles().add(roles.findById(r).orElseThrow());
        u = users.save(u);
        return tokens.issue(u.getId(), u.getEmail(), List.of(roleCodes)).value();
    }

    private int post(String path, String token, String contentType, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url(path))).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) b.header("Content-Type", contentType);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    @Test
    void adminApi_employeeWithInvalidBody_403_not400() throws Exception {
        assertThat(post("/api/admin/users", token("EMPLOYEE"), "application/json", "{}")).isEqualTo(403);
        assertThat(post("/api/admin/users", token("EMPLOYEE", "FINANCE_HEAD"), "application/json", "{}")).isEqualTo(403);
    }

    @Test
    void receiptUpload_withoutFile_isClientError_not500() throws Exception {
        String emp = token("EMPLOYEE");
        assertThat(post("/api/receipts", emp, "application/json", "{}")).as("not multipart").isEqualTo(415);
        assertThat(post("/api/receipts", emp, "multipart/form-data; boundary=x", "--x--\r\n")).as("multipart without file").isEqualTo(400);
    }
}
