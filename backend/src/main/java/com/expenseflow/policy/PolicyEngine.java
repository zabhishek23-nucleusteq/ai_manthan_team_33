package com.expenseflow.policy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.expenseflow.domain.ApprovalLevel;
import com.expenseflow.domain.PolicyAction;
import com.expenseflow.domain.PolicyOutcome;
import com.expenseflow.domain.PolicyRule;
import com.expenseflow.policy.PolicyModel.LineInput;
import com.expenseflow.policy.PolicyModel.LineResult;
import com.expenseflow.policy.PolicyModel.PolicyContext;
import com.expenseflow.policy.PolicyModel.PolicyResult;
import com.expenseflow.policy.PolicyModel.RuleConfig;
import com.expenseflow.policy.PolicyModel.Violation;
import com.expenseflow.policy.rules.PolicyRules;

/**
 * Runs every enabled rule on every line and decides the outcome (BR-9) and the approval levels a routed
 * claim needs (BR-10..BR-12). Pure: no Spring, no database, deterministic for the same input.
 */
public final class PolicyEngine {

    private final List<PolicyRuleEvaluator> evaluators;

    public PolicyEngine(List<PolicyRuleEvaluator> evaluators) {
        this.evaluators = List.copyOf(evaluators);
    }

    public static PolicyEngine withDefaultRules() {
        return new PolicyEngine(PolicyRules.all());
    }

    public Set<String> ruleCodes() {
        Set<String> s = new LinkedHashSet<>();
        evaluators.forEach(e -> s.add(e.code()));
        return s;
    }

    public PolicyResult evaluate(List<LineInput> lines, Map<String, RuleConfig> rules, PolicyContext context) {
        List<LineInput> ordered = lines.stream().sorted(Comparator.comparingLong(LineInput::lineNo)).toList();
        List<LineResult> lineResults = new ArrayList<>();
        Set<String> reasons = new LinkedHashSet<>();
        boolean anyReject = false;
        boolean anyRoute = false;

        for (LineInput line : ordered) {
            List<Violation> violations = new ArrayList<>();
            for (PolicyRuleEvaluator evaluator : evaluators) {
                RuleConfig rule = rules.get(evaluator.code());
                if (rule == null || !rule.enabled() || rule.action() == null) continue;
                evaluator.evaluate(line, ordered, rule, context).ifPresent(violations::add);
            }
            for (Violation v : violations) {
                reasons.add(v.message());
                anyReject |= v.action() == PolicyAction.REJECT;
                anyRoute |= v.action() == PolicyAction.ROUTE;
            }
            lineResults.add(new LineResult(line.lineNo(), line.lineId(), List.copyOf(violations)));
        }

        BigDecimal total = ordered.stream().map(LineInput::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal autoMax = setting(rules, PolicyRule.AUTO_APPROVE_MAX, new BigDecimal("5000"));

        PolicyOutcome outcome;
        if (anyReject) {
            outcome = PolicyOutcome.AUTO_REJECTED;
        } else if (anyRoute) {
            outcome = PolicyOutcome.ROUTED;
        } else if (total.compareTo(autoMax) <= 0) {
            outcome = PolicyOutcome.AUTO_APPROVED;
            reasons.add("All lines within policy and total " + PolicyFormat.money(total) + " INR is within the auto-approve limit of "
                    + PolicyFormat.money(autoMax) + " INR");
        } else {
            outcome = PolicyOutcome.ROUTED;
            reasons.add("Total " + PolicyFormat.money(total) + " INR is above the auto-approve limit of " + PolicyFormat.money(autoMax) + " INR");
        }

        List<ApprovalLevel> levels = new ArrayList<>();
        if (outcome == PolicyOutcome.ROUTED) {
            levels.add(ApprovalLevel.MANAGER);                                                                   // BR-10
            if (total.compareTo(setting(rules, PolicyRule.CHAIN_AUDITOR_ABOVE, new BigDecimal("25000"))) > 0) {
                levels.add(ApprovalLevel.AUDITOR);                                                               // BR-11
            }
            if (total.compareTo(setting(rules, PolicyRule.CHAIN_HEAD_ABOVE, new BigDecimal("100000"))) > 0) {
                levels.add(ApprovalLevel.HEAD);                                                                  // BR-12
            }
        }

        return new PolicyResult(outcome, List.copyOf(reasons), List.copyOf(lineResults), List.copyOf(levels), total,
                new LinkedHashMap<>(rules));
    }

    private static BigDecimal setting(Map<String, RuleConfig> rules, String code, BigDecimal fallback) {
        RuleConfig r = rules.get(code);
        return r == null || r.limitAmount() == null ? fallback : r.limitAmount();
    }
}
