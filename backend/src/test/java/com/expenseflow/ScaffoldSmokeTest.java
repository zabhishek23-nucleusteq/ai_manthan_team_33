package com.expenseflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;

class ScaffoldSmokeTest extends AbstractIntegrationTest {

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(url(path))).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthIsUpWithoutToken() throws Exception {
        HttpResponse<String> res = get("/actuator/health");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void prometheusIsExposed() throws Exception {
        HttpResponse<String> res = get("/actuator/prometheus");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("jvm_memory_used_bytes");
    }

    @Test
    void protectedApiWithoutTokenIs401() throws Exception {
        assertThat(get("/api/claims").statusCode()).isEqualTo(401);
    }

    @Test
    void openApiDocsArePublic() throws Exception {
        HttpResponse<String> res = get("/v3/api-docs");
        assertThat(res.statusCode()).isEqualTo(200);
        assertThat(res.body()).contains("\"openapi\"");
    }
}
