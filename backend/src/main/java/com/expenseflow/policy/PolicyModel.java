package com.expenseflow.policy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.expenseflow.domain.ApprovalLevel;
import com.expenseflow.domain.ExpenseCategory;
import com.expenseflow.domain.PolicyAction;
import com.expenseflow.domain.PolicyOutcome;
import com.expenseflow.domain.RuleUnit;

/** Value types of the policy engine (pure Java, ADR-002). */
public final class PolicyModel {

    private PolicyModel() {
    }

    /** One policy_rules row as the engine sees it (also stored in the result snapshot). */
    public record RuleConfig(String code, BigDecimal limitAmount, RuleUnit unit, Map<String, Object> params,
                             PolicyAction action, boolean enabled) {

        public RuleConfig {
            params = params == null ? Map.of() : Map.copyOf(params);
        }

        public BigDecimal paramAmount(String key) {
            Object v = params.get(key);
            return v == null ? null : new BigDecimal(v.toString());
        }

        @SuppressWarnings("unchecked")
        public List<String> paramList(String key) {
            Object v = params.get(key);
            return v instanceof List<?> l ? (List<String>) l : List.of();
        }
    }

    /** A claim line to check. {@code receiptSha256} is null when the line has no receipt. */
    public record LineInput(long lineNo, Long lineId, ExpenseCategory category, LocalDate expenseDate,
                            BigDecimal amount, String vendor, String city, Integer nights, String receiptSha256) {

        public boolean hasReceipt() {
            return receiptSha256 != null;
        }
    }

    /**
     * Facts from OTHER submitted, non-rejected claims (drafts do not count), prepared by PolicyService.
     * @param priorMealsByDate  sum of this employee's MEAL lines per expense date (BR-1)
     * @param priorLineKeys     duplicate keys "vendor|amount|date" of existing lines (BR-6), see {@link #lineKey}
     * @param priorReceiptHashes SHA-256 of receipts on existing lines (BR-7)
     */
    public record PolicyContext(Map<LocalDate, BigDecimal> priorMealsByDate, Set<String> priorLineKeys,
                                Set<String> priorReceiptHashes) {

        public static PolicyContext empty() {
            return new PolicyContext(Map.of(), Set.of(), Set.of());
        }

        /** BR-6 key: vendor trimmed + case-insensitive, amount ignoring trailing zeros, expense date. */
        public static String lineKey(String vendor, BigDecimal amount, LocalDate date) {
            if (vendor == null || vendor.isBlank() || amount == null || date == null) return null;
            return vendor.trim().toLowerCase() + "|" + amount.stripTrailingZeros().toPlainString() + "|" + date;
        }
    }

    public record Violation(String code, PolicyAction action, String message) {
    }

    public record LineResult(long lineNo, Long lineId, List<Violation> violations) {
    }

    /** Engine output, stored on the claim as JSON so a later rule change never alters history (ADR-002). */
    public record PolicyResult(PolicyOutcome outcome, List<String> reasons, List<LineResult> lineResults,
                               List<ApprovalLevel> requiredLevels, BigDecimal total, Map<String, RuleConfig> ruleSnapshot) {

        public boolean hasViolations() {
            return lineResults.stream().anyMatch(l -> !l.violations().isEmpty());
        }
    }
}
