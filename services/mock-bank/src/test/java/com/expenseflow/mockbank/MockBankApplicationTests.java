package com.expenseflow.mockbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

@SpringBootTest(properties = "mockbank.faults.timeout-ms=200")
@AutoConfigureMockMvc
@AutoConfigureMetrics
class MockBankApplicationTests {

    static final MediaType CSV = MediaType.parseMediaType("text/csv");
    static final String HEADER =
            "RECORD_TYPE,BATCH_ID,VALUE_DATE,EMPLOYEE_CODE,BENEFICIARY_NAME,ACCOUNT_NO,IFSC,AMOUNT,CURRENCY,NARRATION\n";

    @Autowired
    MockMvc mvc;

    /** A valid BR-17 file with two D rows (1250.50 + 99.00) for a fresh batch id. */
    static String goodFile(String batch) {
        return HEADER
                + "D," + batch + ",2026-11-27,EMP0001,Asha Rao,123456789012,HDFC0001234,1250.50,INR,DEMO-1001\n"
                + "D," + batch + ",2026-11-27,EMP0002,\"Mehta, Ravi\",98765432,ICIC0004321,99.00,INR,DEMO-1002\n"
                + "T," + batch + ",2026-11-27,2,1349.50\n";
    }

    static String newBatch() {
        return "PB-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @AfterEach
    void resetFaults() throws Exception {
        mvc.perform(delete("/admin/faults")).andExpect(status().isOk());
    }

    private ResultActions upload(String csv) throws Exception {
        return mvc.perform(post("/bank/payout-files").contentType(CSV).content(csv));
    }

    private void setFaults(String json) throws Exception {
        mvc.perform(post("/admin/faults").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isOk());
    }

    // ---- upload ----

    @Test
    void uploadOk_br17File() throws Exception {
        String batch = newBatch();
        upload(goodFile(batch))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileId").value(startsWith("PF-")))
                .andExpect(jsonPath("$.batchId").value(batch))
                .andExpect(jsonPath("$.valueDate").value("2026-11-27"))
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.acceptedAmount").value(1349.50))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void uploadOk_multipart() throws Exception {
        mvc.perform(multipart("/bank/payout-files")
                        .file(new MockMultipartFile("file", "payout.csv", "text/csv", goodFile(newBatch()).getBytes())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(2));
    }

    @Test
    void uploadPartiallyAccepted_reportsRowErrors() throws Exception {
        String b = newBatch();
        String csv = HEADER
                + "D," + b + ",2026-11-27,EMP1,A,123456789,HDFC0001234,100.00,INR,C-1\n"
                + "D," + b + ",2026-11-27,EMP2,B,12ab,HDFC0001234,100.00,INR,C-2\n"
                + "T," + b + ",2026-11-27,2,200.00\n";
        upload(csv)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(1))
                .andExpect(jsonPath("$.status").value("PARTIALLY_ACCEPTED"))
                .andExpect(jsonPath("$.errors[0].line").value(3))
                .andExpect(jsonPath("$.errors[0].reference").value("C-2"))
                .andExpect(jsonPath("$.errors[0].reason").value(containsString("ACCOUNT_NO")));
    }

    @Test
    void uploadOldHeader_is400() throws Exception {
        upload("reference,employee_id,payee_name,account_number,ifsc,amount_minor,currency\nX,E,N,123456,HDFC0001234,1,INR\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"))
                .andExpect(jsonPath("$.message").value(containsString("RECORD_TYPE,BATCH_ID")));
    }

    @Test
    void uploadTrailerMismatch_is400() throws Exception {
        String b = newBatch();
        upload(goodFile(b).replace(",2,1349.50", ",2,1.00"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("total")));
    }

    @Test
    void uploadEmptyBody_is400() throws Exception {
        mvc.perform(post("/bank/payout-files").contentType(CSV))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"));
    }

    @Test
    void getFileById_byBatch_andUnknownIs404() throws Exception {
        String batch = newBatch();
        String body = upload(goodFile(batch)).andReturn().getResponse().getContentAsString();
        String fileId = body.replaceAll(".*\"fileId\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/bank/payout-files/" + fileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value(fileId));
        mvc.perform(get("/bank/payout-files").param("batchId", batch))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value(fileId));
        mvc.perform(get("/bank/payout-files/PF-nope")).andExpect(status().isNotFound());
        mvc.perform(get("/bank/payout-files").param("batchId", "PB-never")).andExpect(status().isNotFound());
    }

    // ---- idempotency ----

    @Test
    void sameBatch_replaysSameResult_withoutHeader() throws Exception {
        String file = goodFile(newBatch());
        String first = upload(file)
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();
        upload(file)
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(content().json(first));
    }

    @Test
    void sameBatch_differentContent_is422() throws Exception {
        String b = newBatch();
        upload(goodFile(b)).andExpect(status().isCreated());
        upload(goodFile(b).replace("Asha Rao", "Someone Else"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void sameIdempotencyKey_differentBatch_is422() throws Exception {
        mvc.perform(post("/bank/payout-files").contentType(CSV).content(goodFile(newBatch()))
                        .header("Idempotency-Key", "key-" + UUID.randomUUID()))
                .andExpect(status().isCreated());
        String key = "key-" + UUID.randomUUID();
        mvc.perform(post("/bank/payout-files").contentType(CSV).content(goodFile(newBatch())).header("Idempotency-Key", key))
                .andExpect(status().isCreated());
        mvc.perform(post("/bank/payout-files").contentType(CSV).content(goodFile(newBatch())).header("Idempotency-Key", key))
                .andExpect(status().isUnprocessableContent());
    }

    // ---- fault injection ----

    @Test
    void faultMode500_failsBankCalls_butNotAdmin() throws Exception {
        setFaults("{\"errorRate\":1.0,\"latencyMs\":0,\"mode\":\"500\"}");
        upload(goodFile(newBatch()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("BANK_UNAVAILABLE"));
        mvc.perform(get("/admin/faults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("500"));
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }

    @Test
    void faultMode500_withZeroErrorRate_neverFails() throws Exception {
        setFaults("{\"errorRate\":0.0,\"latencyMs\":0,\"mode\":\"500\"}");
        upload(goodFile(newBatch())).andExpect(status().isCreated());
    }

    @Test
    void faultModeTimeout_hangsThen504() throws Exception {
        setFaults("{\"errorRate\":1.0,\"latencyMs\":0,\"mode\":\"timeout\"}");
        long start = System.nanoTime();
        upload(goodFile(newBatch()))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("BANK_TIMEOUT"));
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(200);
    }

    @Test
    void latencyInjected() throws Exception {
        setFaults("{\"errorRate\":0.0,\"latencyMs\":300,\"mode\":\"ok\"}");
        long start = System.nanoTime();
        upload(goodFile(newBatch())).andExpect(status().isCreated());
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(300);
    }

    @Test
    void resetRestoresDefaults() throws Exception {
        setFaults("{\"errorRate\":1.0,\"latencyMs\":0,\"mode\":\"500\"}");
        mvc.perform(delete("/admin/faults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ok"))
                .andExpect(jsonPath("$.latencyMs").value(0));
        upload(goodFile(newBatch())).andExpect(status().isCreated());
    }

    @Test
    void invalidFaultConfig_is400() throws Exception {
        mvc.perform(post("/admin/faults").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorRate\":1.5,\"latencyMs\":0,\"mode\":\"ok\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/admin/faults").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorRate\":0.5,\"latencyMs\":-1,\"mode\":\"ok\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/admin/faults").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"errorRate\":0.5,\"latencyMs\":0,\"mode\":\"boom\"}"))
                .andExpect(status().isBadRequest());
    }

    // ---- actuator ----

    @Test
    void actuatorHealthAndPrometheus() throws Exception {
        upload(goodFile(newBatch())).andExpect(status().isCreated());
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mockbank_payout_files_total")))
                .andExpect(content().string(containsString("mockbank_payout_lines_total")));
    }
}
