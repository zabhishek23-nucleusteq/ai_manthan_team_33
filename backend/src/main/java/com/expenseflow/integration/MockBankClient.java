package com.expenseflow.integration;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Client for the mock bank (services/mock-bank). POST /bank/payout-files with the BR-17 CSV and an Idempotency-Key:
 * the bank stores the result per key, so retrying the same batch with the same key after a timeout returns the
 * stored result instead of paying again. Read timeout 3 s (T-017).
 */
@Component
public class MockBankClient {

    /** Bank answer (mock-bank PayoutFileResult). status: ACCEPTED | PARTIALLY_ACCEPTED | REJECTED. */
    public record BankResult(String fileId, String batchId, int accepted, int rejected, BigDecimal acceptedAmount,
                             String status, List<RowError> errors) {
        public record RowError(int line, String reference, String reason) {
        }
    }

    /** The bank could not be reached, timed out, or answered 5xx/4xx. {@code kind}: timeout | http_5xx | http_4xx | io. */
    public static class BankException extends RuntimeException {
        private final String kind;

        public BankException(String kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }

        public String kind() {
            return kind;
        }
    }

    private final RestClient rest;

    public MockBankClient(@Value("${app.bank.url:http://localhost:8081}") String baseUrl,
                          @Value("${app.bank.timeout:PT3S}") Duration timeout) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        this.rest = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    public BankResult submit(String csv, String idempotencyKey) {
        try {
            return rest.post().uri("/bank/payout-files")
                    .contentType(MediaType.parseMediaType("text/csv"))
                    .header("Idempotency-Key", idempotencyKey)
                    .body(csv)
                    .retrieve()
                    .body(BankResult.class);
        } catch (RestClientResponseException e) {
            String kind = e.getStatusCode().is5xxServerError() ? "http_5xx" : "http_4xx";
            throw new BankException(kind, "Bank answered HTTP " + e.getStatusCode().value() + ": " + abbreviate(e.getResponseBodyAsString()), e);
        } catch (ResourceAccessException e) {
            boolean timeout = e.getMessage() != null && e.getMessage().toLowerCase().contains("timed out")
                    || e.getCause() instanceof java.net.http.HttpTimeoutException;
            throw new BankException(timeout ? "timeout" : "io", (timeout ? "Bank did not answer within the timeout" : "Bank unreachable") + ": " + e.getMessage(), e);
        }
    }

    private static String abbreviate(String s) {
        return s == null ? "" : s.length() > 300 ? s.substring(0, 300) : s;
    }
}
