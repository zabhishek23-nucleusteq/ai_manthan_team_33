package com.expenseflow.domain;

/** Approval chain levels (BR-10..BR-12): manager, auditor above 25,000 INR, head above 1,00,000 INR. */
public enum ApprovalLevel {
    MANAGER, AUDITOR, HEAD
}
