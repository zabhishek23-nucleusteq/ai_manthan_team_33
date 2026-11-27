package com.expenseflow.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.domain.ExpenseCategory;
import com.expenseflow.domain.PolicyAction;
import com.expenseflow.domain.PolicyRule;
import com.expenseflow.policy.PolicyRuleAdminService;
import com.expenseflow.policy.PolicyRuleAdminService.Change;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import tools.jackson.databind.json.JsonMapper;

/** Policy rules as configuration (FR-5, ADR-002). Roles per docs/architecture/rbac.md section 3. */
@RestController
@RequestMapping("/api/policy-rules")
@Validated
public class PolicyRuleController {

    /** contracts/openapi.yaml PolicyRule. Settings rows report action CONFIG; the sample rate reports unit RATE. */
    public record PolicyRuleResponse(String code, String description, ExpenseCategory category, BigDecimal limitAmount,
                                     String unit, Map<String, Object> params, String action, boolean enabled, Instant updatedAt) {
    }

    /** contracts/openapi.yaml PolicyRuleInput. Null fields stay unchanged. */
    public record PolicyRuleInput(@DecimalMin("0") BigDecimal limitAmount, Map<String, Object> params,
                                  PolicyAction action, Boolean enabled) {
    }

    private final PolicyRuleAdminService service;
    private final JsonMapper json;

    public PolicyRuleController(PolicyRuleAdminService service, JsonMapper json) {
        this.service = service;
        this.json = json;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('MANAGER','FINANCE_AUDITOR','FINANCE_HEAD','SUPER_ADMIN')")
    public List<PolicyRuleResponse> list() {
        return service.list().stream().map(this::toResponse).toList();
    }

    @PutMapping("/{code}")
    @PreAuthorize("hasRole('FINANCE_HEAD')")
    public PolicyRuleResponse update(@PathVariable String code, @Valid @RequestBody PolicyRuleInput input,
                                     @AuthenticationPrincipal Jwt jwt) {
        Long actorId = jwt == null ? null : Long.valueOf(jwt.getSubject());
        PolicyRule updated = service.update(code, new Change(input.limitAmount(), input.params(), input.action(), input.enabled()), actorId);
        return toResponse(updated);
    }

    @SuppressWarnings("unchecked")
    private PolicyRuleResponse toResponse(PolicyRule r) {
        String unit = PolicyRule.SAMPLE_AUDIT_RATE.equals(r.getCode()) ? "RATE" : (r.getUnit() == null ? null : r.getUnit().name());
        String action = r.isSetting() ? "CONFIG" : r.getAction().name();
        Map<String, Object> params = r.getParams() == null ? Map.of() : json.readValue(r.getParams(), Map.class);
        return new PolicyRuleResponse(r.getCode(), r.getDescription(), r.getCategory(), r.getLimitAmount(), unit, params,
                action, r.isEnabled(), r.getUpdatedAt());
    }
}
