package com.expenseflow.repository;


import org.springframework.data.jpa.repository.JpaRepository;

import com.expenseflow.domain.PayoutItem;

public interface PayoutItemRepository extends JpaRepository<PayoutItem, Long> {

    boolean existsByClaimId(Long claimId);
}
