package com.expenseflow.domain;

/** Claim state machine statuses (docs/architecture/README.md section 3). Only the claim workflow changes them. */
public enum ClaimStatus {
    DRAFT, PENDING_MANAGER, PENDING_AUDITOR, PENDING_HEAD, CLARIFICATION_REQUESTED, APPROVED, REJECTED, AUTO_REJECTED, IN_PAYOUT, PAID
}
