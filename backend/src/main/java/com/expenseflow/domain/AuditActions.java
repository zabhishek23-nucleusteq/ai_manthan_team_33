package com.expenseflow.domain;

/**
 * Shared audit action names (audit_events.action). One list for every module so the claim timeline,
 * the admin audit filter and the seed data use the same words. Entity types are in AuditService.
 */
public final class AuditActions {

    private AuditActions() {
    }

    public static final String CLAIM_CREATED = "CLAIM_CREATED";
    public static final String CLAIM_SUBMITTED = "CLAIM_SUBMITTED";
    /** Policy engine outcomes (system actor). */
    public static final String CLAIM_AUTO_APPROVED = "CLAIM_AUTO_APPROVED";
    public static final String CLAIM_AUTO_REJECTED = "CLAIM_AUTO_REJECTED";
    public static final String CLAIM_ROUTED = "CLAIM_ROUTED";
    /** Approver decisions (actor = approver; on_behalf_of = original assignee when delegated). */
    public static final String CLAIM_APPROVED = "CLAIM_APPROVED";
    public static final String CLAIM_REJECTED = "CLAIM_REJECTED";
    public static final String CLAIM_SENT_BACK = "CLAIM_SENT_BACK";
    public static final String CLAIM_BULK_APPROVED = "CLAIM_BULK_APPROVED";
    public static final String CLAIM_SAMPLE_AUDIT_FLAGGED = "CLAIM_SAMPLE_AUDIT_FLAGGED";
    public static final String CLAIM_SAMPLE_AUDIT_OK = "CLAIM_SAMPLE_AUDIT_OK";
    public static final String CLAIM_SAMPLE_AUDIT_EXCEPTION = "CLAIM_SAMPLE_AUDIT_EXCEPTION";
    public static final String CLAIM_PAID = "CLAIM_PAID";
    public static final String PAYOUT_CREATED = "PAYOUT_CREATED";
    public static final String PAYOUT_SUBMITTED = "PAYOUT_SUBMITTED";
    public static final String POLICY_RULE_UPDATED = "POLICY_RULE_UPDATED";
    public static final String DELEGATION_CREATED = "DELEGATION_CREATED";
    public static final String ROLE_CHANGED = "ROLE_CHANGED";
    public static final String USER_DISABLED = "USER_DISABLED";
}
