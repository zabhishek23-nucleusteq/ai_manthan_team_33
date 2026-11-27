package com.expenseflow.policy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.domain.Claim;
import com.expenseflow.domain.ClaimLine;
import com.expenseflow.domain.ClaimStatus;
import com.expenseflow.domain.PolicyRule;
import com.expenseflow.policy.PolicyModel.LineInput;
import com.expenseflow.policy.PolicyModel.LineResult;
import com.expenseflow.policy.PolicyModel.PolicyContext;
import com.expenseflow.policy.PolicyModel.PolicyResult;
import com.expenseflow.policy.PolicyModel.RuleConfig;
import com.expenseflow.repository.ClaimLineRepository;
import com.expenseflow.repository.PolicyRuleRepository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs the pure {@link PolicyEngine} against a persisted claim: loads the current rule rows (ADR-002),
 * gathers cross-claim facts from OTHER submitted, non-rejected claims (drafts never count), and writes
 * the result snapshot onto the claim and its lines. Used by the submit workflow.
 */
@Service
public class PolicyService {

    /** Claims in these statuses are ignored for meal sums and duplicate detection. */
    static final Set<ClaimStatus> NOT_COUNTED = EnumSet.of(ClaimStatus.DRAFT, ClaimStatus.REJECTED, ClaimStatus.AUTO_REJECTED);

    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final PolicyRuleRepository rules;
    private final ClaimLineRepository lines;
    private final JsonMapper json;
    private final PolicyEngine engine = PolicyEngine.withDefaultRules();

    public PolicyService(PolicyRuleRepository rules, ClaimLineRepository lines, JsonMapper json) {
        this.rules = rules;
        this.lines = lines;
        this.json = json;
    }

    /** Current rule rows by code (read fresh on every evaluation, so a rule change applies to the next submit). */
    @Transactional(readOnly = true)
    public Map<String, RuleConfig> currentRules() {
        Map<String, RuleConfig> m = new LinkedHashMap<>();
        for (PolicyRule r : rules.findAllByOrderByCodeAsc()) {
            m.put(r.getCode(), toConfig(r));
        }
        return m;
    }

    public RuleConfig toConfig(PolicyRule r) {
        Map<String, Object> params = r.getParams() == null ? Map.of() : json.readValue(r.getParams(), MAP);
        return new RuleConfig(r.getCode(), r.getLimitAmount(), r.getUnit(), params, r.getAction(), r.isEnabled());
    }

    /** Evaluates the claim as it is now (does not change it). */
    @Transactional(readOnly = true)
    public PolicyResult evaluate(Claim claim) {
        List<LineInput> inputs = claim.getLines().stream().map(PolicyService::toInput).toList();
        return engine.evaluate(inputs, currentRules(), contextFor(claim, inputs));
    }

    /**
     * Evaluates and stores the outcome, the JSON snapshot on the claim and each line's violations.
     * The caller (submit workflow) then moves the claim through the state machine based on the outcome.
     */
    @Transactional
    public PolicyResult evaluateAndApply(Claim claim) {
        PolicyResult result = evaluate(claim);
        claim.setPolicyOutcome(result.outcome());
        claim.setPolicyResult(json.writeValueAsString(result));
        Map<Long, LineResult> byNo = result.lineResults().stream()
                .collect(Collectors.toMap(LineResult::lineNo, l -> l));
        for (ClaimLine line : claim.getLines()) {
            LineResult lr = byNo.get((long) line.getLineNo());
            line.setViolations(lr == null || lr.violations().isEmpty() ? null : json.writeValueAsString(lr.violations()));
        }
        return result;
    }

    /** Reads a stored snapshot back (e.g. for the claim detail API). */
    public PolicyResult readSnapshot(String policyResultJson) {
        return policyResultJson == null ? null : json.readValue(policyResultJson, PolicyResult.class);
    }

    static LineInput toInput(ClaimLine l) {
        return new LineInput(l.getLineNo(), l.getId(), l.getCategory(), l.getExpenseDate(), l.getAmount(), l.getVendor(),
                l.getCity(), l.getNights(), l.getReceipt() == null ? null : l.getReceipt().getSha256());
    }

    PolicyContext contextFor(Claim claim, List<LineInput> inputs) {
        if (inputs.isEmpty()) return PolicyContext.empty();
        Long claimId = claim.getId() == null ? -1L : claim.getId();
        Set<LocalDate> dates = inputs.stream().map(LineInput::expenseDate).collect(Collectors.toSet());

        Map<LocalDate, BigDecimal> meals = new HashMap<>();
        if (claim.getEmployee() != null && claim.getEmployee().getId() != null) {
            for (Object[] row : lines.sumMealsByDate(claim.getEmployee().getId(), claimId, NOT_COUNTED, dates)) {
                meals.put((LocalDate) row[0], (BigDecimal) row[1]);
            }
        }

        Set<String> keys = new HashSet<>();
        for (Object[] row : lines.findVendorAmountDate(claimId, NOT_COUNTED, dates)) {
            String key = PolicyContext.lineKey((String) row[0], (BigDecimal) row[1], (LocalDate) row[2]);
            if (key != null) keys.add(key);
        }

        Set<String> hashes = inputs.stream().map(LineInput::receiptSha256).filter(Objects::nonNull).collect(Collectors.toSet());
        Set<String> used = hashes.isEmpty() ? Set.of() : new HashSet<>(lines.findUsedReceiptHashes(claimId, NOT_COUNTED, hashes));

        return new PolicyContext(meals, keys, used);
    }
}
