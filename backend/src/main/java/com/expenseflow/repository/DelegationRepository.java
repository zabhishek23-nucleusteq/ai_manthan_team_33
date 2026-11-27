package com.expenseflow.repository;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.expenseflow.domain.Delegation;

public interface DelegationRepository extends JpaRepository<Delegation, Long> {

    List<Delegation> findByDelegatorIdOrderByFromDateDesc(Long delegatorId);

    /** Active delegation of an approver covering the given date, if any (one hop only, BR-14). */
    @Query("select d from Delegation d where d.delegator.id = :delegatorId and d.active = true and :date between d.fromDate and d.toDate")
    List<Delegation> findActiveFor(Long delegatorId, LocalDate date);
}