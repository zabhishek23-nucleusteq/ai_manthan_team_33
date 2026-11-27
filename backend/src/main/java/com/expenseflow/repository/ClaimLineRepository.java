package com.expenseflow.repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.JpaRepository;

import com.expenseflow.domain.ClaimLine;

public interface ClaimLineRepository extends JpaRepository<ClaimLine, Long> {

    /** Earlier lines with the same vendor (case-insensitive), amount and date on other claims - duplicate rule. */
    @Query("select l.id from ClaimLine l where lower(l.vendor) = lower(:vendor) and l.amount = :amount and l.expenseDate = :date and l.claim.id <> :claimId")
    List<Long> findDuplicateLineIds(String vendor, BigDecimal amount, LocalDate date, Long claimId);
}
