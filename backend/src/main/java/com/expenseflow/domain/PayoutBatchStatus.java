package com.expenseflow.domain;

/** Payout batch lifecycle. */
public enum PayoutBatchStatus {
    CREATED, SUBMITTED, PAID, SUBMIT_FAILED, CANCELLED
}
