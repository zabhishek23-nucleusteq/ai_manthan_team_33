package com.expenseflow.domain;

/** Decision on an approval step; null on the step means pending. */
public enum ApprovalDecision {
    APPROVED, REJECTED, SENT_BACK
}
