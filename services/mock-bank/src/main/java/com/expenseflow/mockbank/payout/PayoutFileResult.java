package com.expenseflow.mockbank.payout;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Response of the bank for an uploaded payout file. {@code reference} in a row error is the NARRATION (claim code). */
public record PayoutFileResult(
        String fileId,
        String batchId,
        LocalDate valueDate,
        int accepted,
        int rejected,
        BigDecimal acceptedAmount,
        String status,
        List<RowError> errors,
        Instant receivedAt) {

    public record RowError(int line, String reference, String reason) {
    }
}
