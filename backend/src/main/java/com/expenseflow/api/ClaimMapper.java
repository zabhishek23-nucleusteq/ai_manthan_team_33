package com.expenseflow.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.expenseflow.api.ClaimDtos.ApprovalStepDto;
import com.expenseflow.api.ClaimDtos.ClaimDto;
import com.expenseflow.api.ClaimDtos.ClaimLineDto;
import com.expenseflow.api.ClaimDtos.ClaimSummary;
import com.expenseflow.api.ClaimDtos.CommentDto;
import com.expenseflow.api.ClaimDtos.DepartmentDto;
import com.expenseflow.api.ClaimDtos.LineResultDto;
import com.expenseflow.api.ClaimDtos.PolicyResultDto;
import com.expenseflow.api.ClaimDtos.ReceiptDto;
import com.expenseflow.api.ClaimDtos.UserRef;
import com.expenseflow.api.ClaimDtos.ViolationDto;
import com.expenseflow.domain.ApprovalStep;
import com.expenseflow.domain.Claim;
import com.expenseflow.domain.ClaimComment;
import com.expenseflow.domain.ClaimLine;
import com.expenseflow.domain.Department;
import com.expenseflow.domain.Receipt;
import com.expenseflow.domain.User;
import com.expenseflow.policy.PolicyModel.PolicyResult;
import com.expenseflow.policy.PolicyModel.Violation;
import com.expenseflow.policy.PolicyService;
import com.expenseflow.repository.ApprovalStepRepository;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Entities -> contract DTOs. Call inside a transaction (lazy relations). */
@Component
public class ClaimMapper {

    private static final TypeReference<List<Violation>> VIOLATIONS = new TypeReference<>() { };

    private final PolicyService policy;
    private final ApprovalStepRepository steps;
    private final JsonMapper json;

    public ClaimMapper(PolicyService policy, ApprovalStepRepository steps, JsonMapper json) {
        this.policy = policy;
        this.steps = steps;
        this.json = json;
    }

    public static UserRef ref(User u) {
        return u == null ? null : new UserRef(u.getId(), u.getName(), u.getEmail());
    }

    public static DepartmentDto dept(Department d) {
        return d == null ? null : new DepartmentDto(d.getId(), d.getName());
    }

    public static ReceiptDto receipt(Receipt r) {
        return r == null ? null : new ReceiptDto(r.getId(), r.getFileName(), r.getContentType(), r.getSize(), r.getSha256());
    }

    private List<ViolationDto> violations(String jsonText) {
        if (jsonText == null) return List.of();
        return json.readValue(jsonText, VIOLATIONS).stream().map(v -> new ViolationDto(v.code(), v.action(), v.message())).toList();
    }

    public ClaimDto toDto(Claim c) {
        List<ClaimLineDto> lines = c.getLines().stream().map(l -> new ClaimLineDto(l.getId(), l.getCategory(), l.getAmount(),
                l.getExpenseDate(), l.getVendor(), l.getDescription(), l.getCity(), l.getNights(),
                l.getReceipt() == null ? null : l.getReceipt().getId(), receipt(l.getReceipt()), violations(l.getViolations()))).toList();

        PolicyResultDto pr = null;
        PolicyResult snap = policy.readSnapshot(c.getPolicyResult());
        if (snap != null) {
            pr = new PolicyResultDto(snap.outcome(), snap.reasons(),
                    snap.lineResults().stream().map(lr -> new LineResultDto(lr.lineId(),
                            lr.violations().stream().map(v -> new ViolationDto(v.code(), v.action(), v.message())).toList())).toList(),
                    snap.requiredLevels());
        }
        List<ApprovalStepDto> stepDtos = new ArrayList<>();
        if (c.getId() != null) {
            for (ApprovalStep s : steps.findByClaimIdOrderByRoundAscIdAsc(c.getId())) {
                stepDtos.add(new ApprovalStepDto(s.getLevel(), s.getRound(), ref(s.getAssignee()), ref(s.getActedBy()), ref(s.getOnBehalfOf()),
                        s.getDecision() == null ? "PENDING" : s.getDecision().name(), s.getComment(), s.getCreatedAt(), s.getDecidedAt()));
            }
        }
        User e = c.getEmployee();
        return new ClaimDto(c.getId(), c.getCode(), c.getTitle(), c.getStatus(), c.getTotalAmount(), ref(e), dept(e.getDepartment()),
                lines, pr, stepDtos, c.getSampleAudit(), c.getCreatedAt(), c.getSubmittedAt(), c.getDecidedAt(), c.getVersion());
    }

    public ClaimSummary toSummary(Claim c) {
        Set<String> codes = new LinkedHashSet<>();
        for (ClaimLine l : c.getLines()) {
            for (ViolationDto v : violations(l.getViolations())) codes.add(v.code());
        }
        return new ClaimSummary(c.getId(), c.getCode(), c.getTitle(), c.getStatus(), c.getTotalAmount(), c.getLines().size(),
                List.copyOf(codes), c.getSubmittedAt(), c.getUpdatedAt());
    }

    public static CommentDto comment(ClaimComment cm) {
        return new CommentDto(cm.getId(), ref(cm.getAuthor()), cm.getBody(), cm.getCreatedAt());
    }
}
