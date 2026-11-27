package com.expenseflow.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.expenseflow.AbstractIntegrationTest;
import com.expenseflow.domain.PolicyRule;
import com.expenseflow.domain.User;
import com.expenseflow.repository.PolicyRuleRepository;
import com.expenseflow.repository.UserRepository;

/**
 * T-602 / FR-5 + FR-13: a policy rule change writes exactly one audit row in the same transaction as the change,
 * and a change that rolls back leaves neither the new value nor an audit row behind.
 */
class PolicyRuleAuditTest extends AbstractIntegrationTest {

    @Autowired PolicyRuleAdminService admin;
    @Autowired PolicyRuleRepository rules;
    @Autowired UserRepository users;
    @Autowired TransactionTemplate tx;
    @Autowired JdbcTemplate jdbc;

    private long auditRows(String code) {
        return jdbc.queryForObject("select count(*) from audit_events where action = 'POLICY_RULE_UPDATED' "
                + "and entity_type = 'POLICY_RULE' and entity_id = ?", Long.class, code);
    }

    private long head() {
        return users.save(new User("Rule Head", "rule-head-" + UUID.randomUUID() + "@expenseflow.test", "x")).getId();
    }

    @AfterEach
    void restore() {
        admin.update(PolicyRule.MEAL_DAILY_LIMIT, new PolicyRuleAdminService.Change(new BigDecimal("1500"), null, null, null), null);
    }

    @Test
    void policyRuleUpdate_writesOneAuditEvent() {
        long before = auditRows(PolicyRule.MEAL_DAILY_LIMIT);
        long actor = head();

        admin.update(PolicyRule.MEAL_DAILY_LIMIT, new PolicyRuleAdminService.Change(new BigDecimal("1750"), null, null, null), actor);

        assertThat(auditRows(PolicyRule.MEAL_DAILY_LIMIT)).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("""
                select actor_id = ? and (before->>'limitAmount')::numeric = 1500 and (after->>'limitAmount')::numeric = 1750
                from audit_events where entity_type = 'POLICY_RULE' and entity_id = ? order by id desc limit 1""",
                Boolean.class, actor, PolicyRule.MEAL_DAILY_LIMIT)).as("actor + before/after limit").isTrue();
    }

    @Test
    void policyRuleUpdate_failed_noAuditEvent() {
        long before = auditRows(PolicyRule.MEAL_DAILY_LIMIT);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
            admin.update(PolicyRule.MEAL_DAILY_LIMIT, new PolicyRuleAdminService.Change(new BigDecimal("9999"), null, null, null), head());
            throw new IllegalStateException("caller fails after the rule change");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(auditRows(PolicyRule.MEAL_DAILY_LIMIT)).as("audit row rolls back with the change").isEqualTo(before);
        assertThat(rules.findById(PolicyRule.MEAL_DAILY_LIMIT).orElseThrow().getLimitAmount()).isEqualByComparingTo("1500");
    }

    @Test
    void policyRuleUpdate_rejectedByValidation_noAuditEvent() {
        long before = auditRows(PolicyRule.SAMPLE_AUDIT_RATE);
        assertThatThrownBy(() -> admin.update(PolicyRule.SAMPLE_AUDIT_RATE,
                new PolicyRuleAdminService.Change(new BigDecimal("1.5"), null, null, null), head()))
                .hasMessageContaining("between 0 and 1");
        assertThat(auditRows(PolicyRule.SAMPLE_AUDIT_RATE)).isEqualTo(before);
    }
}
