package com.expenseflow.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.expenseflow.domain.PayoutBatch;

import jakarta.persistence.LockModeType;

public interface PayoutBatchRepository extends JpaRepository<PayoutBatch, Long> {

    List<PayoutBatch> findAllByOrderByCreatedAtDesc();

    Page<PayoutBatch> findAllBy(Pageable pageable);

    /** Row lock so two submits of the same batch cannot both call the bank. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from PayoutBatch b where b.id = :id")
    Optional<PayoutBatch> findByIdForUpdate(Long id);
}