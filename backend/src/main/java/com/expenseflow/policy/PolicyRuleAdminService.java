package com.expenseflow.policy;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.api.ApiException;
import com.expenseflow.domain.PolicyAction;
import com.expenseflow.domain.PolicyRule;
import com.expenseflow.repository.PolicyRuleRepository;
import com.expenseflow.repository.UserRepository;

import tools.jackson.databind.json.JsonMapper;

/** Reads and updates policy rule rows (FR-5). Updates publish {@link PolicyRuleChangedEvent} for the audit trail. */
@Service
public class PolicyRuleAdminService {

    /** Partial update: null fields are left unchanged. */
    public record Change(BigDecimal limitAmount, Map<String, Object> params, PolicyAction action, Boolean enabled) {
    }

    private final PolicyRuleRepository rules;
    private final UserRepository users;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;

    public PolicyRuleAdminService(PolicyRuleRepository rules, UserRepository users, JsonMapper json, ApplicationEventPublisher events) {
        this.rules = rules;
        this.users = users;
        this.json = json;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public List<PolicyRule> list() {
        return rules.findAllByOrderByCodeAsc();
    }

    @Transactional
    public PolicyRule update(String code, Change change, Long actorId) {
        PolicyRule rule = rules.findById(code)
                .orElseThrow(() -> ApiException.notFound("POLICY_RULE_NOT_FOUND", "No policy rule " + code));
        validate(rule, change);
        Map<String, Object> before = snapshot(rule);

        if (change.limitAmount() != null) rule.setLimitAmount(change.limitAmount());
        if (change.params() != null) rule.setParams(json.writeValueAsString(change.params()));
        if (change.action() != null) rule.setAction(change.action());
        if (change.enabled() != null) rule.setEnabled(change.enabled());
        if (actorId != null) users.findById(actorId).ifPresent(rule::setUpdatedBy);
        rules.saveAndFlush(rule);

        events.publishEvent(new PolicyRuleChangedEvent(code, actorId, before, snapshot(rule)));
        return rule;
    }

    private static void validate(PolicyRule rule, Change c) {
        if (rule.isSetting() && c.action() != null) {
            throw invalid(rule.getCode() + " is a setting and has no action");
        }
        if (PolicyRule.SAMPLE_AUDIT_RATE.equals(rule.getCode()) && c.limitAmount() != null
                && c.limitAmount().compareTo(BigDecimal.ONE) > 0) {
            throw invalid("SAMPLE_AUDIT_RATE must be between 0 and 1");
        }
        if (PolicyRule.HOTEL_NIGHTLY_LIMIT.equals(rule.getCode()) && c.params() != null) {
            Object other = c.params().get("otherCitiesLimit");
            if (other == null || new BigDecimal(other.toString()).signum() < 0) {
                throw invalid("HOTEL_NIGHTLY_LIMIT params need otherCitiesLimit >= 0");
            }
            if (!(c.params().get("tier1Cities") instanceof List<?> l) || l.isEmpty()) {
                throw invalid("HOTEL_NIGHTLY_LIMIT params need a non-empty tier1Cities list");
            }
        }
        if (PolicyRule.CHAIN_AUDITOR_ABOVE.equals(rule.getCode()) || PolicyRule.CHAIN_HEAD_ABOVE.equals(rule.getCode())
                || PolicyRule.AUTO_APPROVE_MAX.equals(rule.getCode())) {
            if (c.enabled() != null && !c.enabled()) throw invalid(rule.getCode() + " cannot be disabled");
        }
    }

    private static ApiException invalid(String message) {
        return ApiException.refused("POLICY_RULE_INVALID", message, null);
    }

    private Map<String, Object> snapshot(PolicyRule r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("limitAmount", r.getLimitAmount());
        m.put("params", r.getParams() == null ? null : json.readValue(r.getParams(), Map.class));
        m.put("action", r.getAction());
        m.put("enabled", r.isEnabled());
        return m;
    }
}
