package com.expenseflow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.expenseflow.domain.ApprovalStep;

public interface ApprovalStepRepository extends JpaRepository<ApprovalStep, Long> {

    /** Pending steps assigned to a user (approver inbox; delegation is resolved by the approval service). */
    List<ApprovalStep> findByAssigneeIdAndDecisionIsNull(Long assigneeId);

    List<ApprovalStep> findByClaimIdOrderByRoundAscIdAsc(Long claimId);

    /** The step currently waiting for a decision on a claim, if any. */
    Optional<ApprovalStep> findFirstByClaimIdAndDecisionIsNull(Long claimId);

    /** rbac.md row rule: the user is or was an approver of the claim (assignee, acted, or delegated from). */
    @org.springframework.data.jpa.repository.Query("""
            select count(s) > 0 from ApprovalStep s where s.claim.id = :claimId
              and (s.assignee.id = :userId or s.actedBy.id = :userId or s.onBehalfOf.id = :userId)""")
    boolean isApproverOf(Long claimId, Long userId);

    /** Highest approval round so far (0 if none) - a resubmit starts the next round (BR-16). */
    @org.springframework.data.jpa.repository.Query("select coalesce(max(s.round), 0) from ApprovalStep s where s.claim.id = :claimId")
    int maxRound(Long claimId);

    /** Receipt row rule: the user is/was an approver of a claim that uses this receipt. */
    @org.springframework.data.jpa.repository.Query("""
            select count(s) > 0 from ApprovalStep s, ClaimLine l where l.receipt.id = :receiptId and s.claim = l.claim
              and (s.assignee.id = :userId or s.actedBy.id = :userId or s.onBehalfOf.id = :userId)""")
    boolean isApproverOfReceipt(Long receiptId, Long userId);
}