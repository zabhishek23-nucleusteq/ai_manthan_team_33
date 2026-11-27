package com.expenseflow.policy;

import java.util.List;
import java.util.Optional;

import com.expenseflow.policy.PolicyModel.LineInput;
import com.expenseflow.policy.PolicyModel.PolicyContext;
import com.expenseflow.policy.PolicyModel.RuleConfig;
import com.expenseflow.policy.PolicyModel.Violation;

/** One rule = one evaluator (ADR-002). Pure: no Spring, no database. */
public interface PolicyRuleEvaluator {

    /** The policy_rules code this evaluator implements. */
    String code();

    /**
     * Checks one line. {@code lines} are all lines of the claim in line order (for per-day sums and
     * duplicates against earlier lines of the same claim).
     */
    Optional<Violation> evaluate(LineInput line, List<LineInput> lines, RuleConfig rule, PolicyContext context);
}
