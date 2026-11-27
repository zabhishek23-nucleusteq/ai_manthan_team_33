package com.expenseflow.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.security.JwtTokenService;

/** T-603: bad query/path parameters are 400 VALIDATION_FAILED, not the 500 catch-all. */
class RequestParamErrorsTest extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired JwtTokenService tokens;

    private HttpResponse<String> get(String pathAndQuery) throws Exception {
        String token = tokens.issue(42, "a@expenseflow.test", List.of("EMPLOYEE")).value();
        return http.send(HttpRequest.newBuilder(URI.create(url(pathAndQuery)))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void assert400(HttpResponse<String> res, String field) {
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("\"code\":\"VALIDATION_FAILED\"").contains("\"" + field + "\"")
                .doesNotContain("Exception");
    }

    @Test
    void valid_is200() throws Exception {
        HttpResponse<String> res = get("/api/test-probe/params/7?page=0&from=2026-11-01T00:00:00Z");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"page\":0");
    }

    @Test
    void mistypedQueryParam_is400() throws Exception {
        assert400(get("/api/test-probe/params/7?page=abc"), "page");
    }

    @Test
    void mistypedDateParam_is400() throws Exception {
        assert400(get("/api/test-probe/params/7?page=0&from=yesterday"), "from");
    }

    @Test
    void mistypedPathVariable_is400() throws Exception {
        assert400(get("/api/test-probe/params/abc?page=0"), "id");
    }

    @Test
    void missingRequiredParam_is400() throws Exception {
        assert400(get("/api/test-probe/params/7"), "page");
    }

    @Test
    void constraintViolationOnParam_is400() throws Exception {
        assert400(get("/api/test-probe/params/7?page=-1"), "page");
        assert400(get("/api/test-probe/params/7?page=0&size=500"), "size");
    }
}
