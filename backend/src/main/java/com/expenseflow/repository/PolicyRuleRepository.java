package com.expenseflow.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.expenseflow.domain.PolicyRule;

public interface PolicyRuleRepository extends JpaRepository<PolicyRule, String> {

    List<PolicyRule> findAllByOrderByCodeAsc();

    List<PolicyRule> findByEnabledTrue();
}