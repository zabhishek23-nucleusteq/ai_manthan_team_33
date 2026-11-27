package com.expenseflow.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

import com.expenseflow.domain.ClaimComment;

public interface ClaimCommentRepository extends JpaRepository<ClaimComment, Long> {

    List<ClaimComment> findByClaimIdOrderByCreatedAtAsc(Long claimId);
}
