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
}