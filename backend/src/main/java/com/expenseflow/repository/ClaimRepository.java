package com.expenseflow.repository;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;

import com.expenseflow.domain.Claim;

public interface ClaimRepository extends JpaRepository<Claim, Long> {

    /** Owner-scoped lookup: other users' claims are simply not found (404, no enumeration). */
    Optional<Claim> findByIdAndEmployeeId(Long id, Long employeeId);

    Page<Claim> findByEmployeeId(Long employeeId, Pageable pageable);

    Page<Claim> findByEmployeeIdAndStatus(Long employeeId, com.expenseflow.domain.ClaimStatus status, Pageable pageable);

    @Query(value = "select nextval('claim_code_seq')", nativeQuery = true)
    long nextCodeNumber();

    /** Row lock for state transitions (approve / reject / send back / payout). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Claim c where c.id = :id")
    Optional<Claim> findByIdForUpdate(Long id);

    boolean existsByCode(String code);

    /**
     * BR-18 payable claims, locked for the caller's transaction. SKIP LOCKED: a concurrent batch creation skips rows
     * already taken, so no claim can land in two batches (payout_items.claim_id is unique as the last guard).
     */
    @Query(value = "select c.id from claims c where c.status = 'APPROVED' and c.sample_audit not in ('PENDING', 'EXCEPTION') "
            + "and not exists (select 1 from payout_items i where i.claim_id = c.id) order by c.id for update skip locked",
            nativeQuery = true)
    java.util.List<Long> lockPayableClaimIds();
}
