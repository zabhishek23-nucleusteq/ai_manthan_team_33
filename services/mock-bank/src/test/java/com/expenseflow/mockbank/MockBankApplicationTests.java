package com.expenseflow.mockbank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
    static final String HEADER = "reference,employee_id,payee_name,account_number,ifsc,amount_minor,currency\n";
    static final String GOOD = HEADER
            + "CLM-1001,EMP-001,Asha Rao,123456789012,HDFC0001234,125050,INR\n"
            + "CLM-1002,EMP-002,\"Mehta, Ravi\",98765432,ICIC0004321,9900,INR\n";

    @Autowired
    MockMvc mvc;

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
    void uploadOk_rawCsv() throws Exception {
        upload(GOOD)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.fileId").value(org.hamcrest.Matchers.startsWith("PF-")))
                .andExpect(jsonPath("$.accepted").value(2))
                .andExpect(jsonPath("$.rejected").value(0))
                .andExpect(jsonPath("$.acceptedAmountMinor").value(134950))
                .andExpect(jsonPath("$.status").value("ACCEPTED"));
    }

    @Test
    void uploadOk_multipart() throws Exception {
        mvc.perform(multipart("/bank/payout-files")
                        .file(new MockMultipartFile("file", "payout.csv", "text/csv", GOOD.getBytes())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(2));
    }

    @Test
    void uploadPartiallyAccepted_reportsRowErrors() throws Exception {
        String csv = HEADER
                + "CLM-1,EMP-1,A,123456789,HDFC0001234,100,INR\n"
                + "CLM-2,EMP-2,B,12ab,HDFC0001234,100,INR\n"      // bad account
                + "CLM-3,EMP-3,C,123456789,BAD,100,INR\n"          // bad ifsc
                + "CLM-4,EMP-4,D,123456789,HDFC0001234,0,INR\n"    // zero amount
                + "CLM-1,EMP-5,E,123456789,HDFC0001234,100,INR\n"  // duplicate reference
                + "CLM-6,EMP-6,F,123456789\n";                     // too few columns
        upload(csv)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(1))
                .andExpect(jsonPath("$.rejected").value(5))
                .andExpect(jsonPath("$.status").value("PARTIALLY_ACCEPTED"))
                .andExpect(jsonPath("$.errors[0].line").value(3))
                .andExpect(jsonPath("$.errors[0].reason").value(containsString("account_number")))
                .andExpect(jsonPath("$.errors[3].reason").value("Duplicate reference in file"));
    }

    @Test
    void uploadAllRowsBad_isRejected() throws Exception {
        upload(HEADER + "CLM-1,EMP-1,A,123456789,HDFC0001234,-5,INR\n")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(0))
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void uploadWrongHeader_is400() throws Exception {
        upload("ref,amount\nX,1\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"));
    }

    @Test
    void uploadHeaderOnly_is400() throws Exception {
        upload(HEADER).andExpect(status().isBadRequest());
    }

    @Test
    void uploadEmptyBody_is400() throws Exception {
        mvc.perform(post("/bank/payout-files").contentType(CSV))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_FILE"));
    }

    @Test
    void getFileById_andUnknownIs404() throws Exception {
        String body = upload(GOOD).andReturn().getResponse().getContentAsString();
        String fileId = body.replaceAll(".*\"fileId\":\"([^\"]+)\".*", "$1");
        mvc.perform(get("/bank/payout-files/" + fileId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileId").value(fileId));
        mvc.perform(get("/bank/payout-files/PF-nope")).andExpect(status().isNotFound());
    }

    // ---- idempotency ----

    @Test
    void sameIdempotencyKey_replaysSameResult() throws Exception {
        String first = mvc.perform(post("/bank/payout-files").contentType(CSV).content(GOOD)
                        .header("Idempotency-Key", "batch-42"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(post("/bank/payout-files").contentType(CSV).content(GOOD)
                        .header("Idempotency-Key", "batch-42"))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(content().json(first));
    }

    @Test
    void sameIdempotencyKey_differentFile_is422() throws Exception {
        mvc.perform(post("/bank/payout-files").contentType(CSV).content(GOOD).header("Idempotency-Key", "batch-43"))
                .andExpect(status().isCreated());
        mvc.perform(post("/bank/payout-files").contentType(CSV).content(GOOD + "CLM-9,E,X,123456,HDFC0001234,1,INR\n")
                        .header("Idempotency-Key", "batch-43"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
    }

    // ---- fault injection ----

    @Test
    void faultMode500_failsBankCalls_butNotAdmin() throws Exception {
        setFaults("{\"errorRate\":1.0,\"latencyMs\":0,\"mode\":\"500\"}");
        upload(GOOD)
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
        upload(GOOD).andExpect(status().isCreated());
    }

    @Test
    void faultModeTimeout_hangsThen504() throws Exception {
        setFaults("{\"errorRate\":1.0,\"latencyMs\":0,\"mode\":\"timeout\"}");
        long start = System.nanoTime();
        upload(GOOD)
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("BANK_TIMEOUT"));
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(200);
    }

    @Test
    void latencyInjected() throws Exception {
        setFaults("{\"errorRate\":0.0,\"latencyMs\":300,\"mode\":\"ok\"}");
        long start = System.nanoTime();
        upload(GOOD).andExpect(status().isCreated());
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(300);
    }

    @Test
    void resetRestoresDefaults() throws Exception {
        setFaults("{\"errorRate\":1.0,\"latencyMs\":0,\"mode\":\"500\"}");
        mvc.perform(delete("/admin/faults"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("ok"))
                .andExpect(jsonPath("$.latencyMs").value(0));
        upload(GOOD).andExpect(status().isCreated());
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
        upload(GOOD).andExpect(status().isCreated());
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("mockbank_payout_files_total")))
                .andExpect(content().string(containsString("mockbank_payout_lines_total")));
    }
}
