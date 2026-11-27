package com.expenseflow.mockbank.payout;

import java.time.Instant;
import java.util.List;

/** Response of the bank for an uploaded payout file. */
public record PayoutFileResult(
        String fileId,
        int accepted,
        int rejected,
        long acceptedAmountMinor,
        String status,
        List<RowError> errors,
        Instant receivedAt) {

    public record RowError(int line, String reference, String reason) {
    }
}
