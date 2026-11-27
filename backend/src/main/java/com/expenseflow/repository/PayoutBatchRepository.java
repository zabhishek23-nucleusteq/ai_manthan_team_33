package com.expenseflow.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.expenseflow.domain.PayoutBatch;

public interface PayoutBatchRepository extends JpaRepository<PayoutBatch, Long> {

    List<PayoutBatch> findAllByOrderByCreatedAtDesc();
}