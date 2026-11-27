package com.expenseflow.mockbank.payout;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class PayoutFileServiceConcurrencyTest {

    static final String CSV =
            "RECORD_TYPE,BATCH_ID,VALUE_DATE,EMPLOYEE_CODE,BENEFICIARY_NAME,ACCOUNT_NO,IFSC,AMOUNT,CURRENCY,NARRATION\n"
            + "D,PB-1,2026-11-27,EMP0001,N,123456,HDFC0001234,500.00,INR,DEMO-1\n"
            + "T,PB-1,2026-11-27,1,500.00\n";

    @Test
    void parallelSubmitsWithSameKey_produceExactlyOneFile() throws Exception {
        PayoutFileService service = new PayoutFileService(new SimpleMeterRegistry());
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<PayoutFileService.Submission>> calls = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                calls.add(() -> service.submit(CSV, "same-key"));
            }
            List<PayoutFileService.Submission> results = new ArrayList<>();
            for (Future<PayoutFileService.Submission> f : pool.invokeAll(calls)) {
                results.add(f.get());
            }
            assertThat(results).extracting(s -> s.result().fileId()).containsOnly(results.get(0).result().fileId());
            assertThat(results).filteredOn(s -> !s.replayed()).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }
}
