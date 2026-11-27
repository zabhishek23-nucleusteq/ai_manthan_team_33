package com.expenseflow.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.expenseflow.domain.ApprovalLevel;
import com.expenseflow.domain.ClaimStatus;
import com.expenseflow.domain.ExpenseCategory;
import com.expenseflow.domain.PolicyAction;
import com.expenseflow.domain.PolicyOutcome;
import com.expenseflow.domain.SampleAudit;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;

/** Request/response shapes of contracts/openapi.yaml for claims, receipts, comments and departments. */
public final class ClaimDtos {

    private ClaimDtos() {
    }

    // ---------------------------------------------------------------- requests
    public record ClaimInput(
            @NotBlank @Size(min = 3, max = 120) String title,
            @NotNull @Size(max = 50) List<@Valid ClaimLineInput> lines) {
    }

    public record ClaimLineInput(
            @NotNull ExpenseCategory category,
            @NotNull @DecimalMin(value = "0", inclusive = false) @DecimalMax("10000000") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotNull @PastOrPresent LocalDate expenseDate,
            @NotBlank @Size(max = 120) String vendor,
            @Size(max = 500) String description,
            @Size(max = 80) String city,
            @Min(1) @Max(30) Integer nights,
            Long receiptId) {
    }

    public record CommentInput(@NotBlank @Size(max = 2000) String text) {
    }

    // ---------------------------------------------------------------- responses
    public record UserRef(Long id, String name, String email) {
    }

    public record DepartmentDto(Long id, String name) {
    }

    public record ReceiptDto(Long id, String fileName, String contentType, long size, String sha256) {
    }

    public record ViolationDto(String code, PolicyAction action, String message) {
    }

    public record ClaimLineDto(Long id, ExpenseCategory category, BigDecimal amount, LocalDate expenseDate, String vendor,
                               String description, String city, Integer nights, Long receiptId, ReceiptDto receipt,
                               List<ViolationDto> violations) {
    }

    public record LineResultDto(Long lineId, List<ViolationDto> violations) {
    }

    public record PolicyResultDto(PolicyOutcome outcome, List<String> reasons, List<LineResultDto> lineResults,
                                  List<ApprovalLevel> requiredLevels) {
    }

    /** decision is PENDING while the step waits (null in the database). */
    public record ApprovalStepDto(ApprovalLevel level, int round, UserRef assignee, UserRef actedBy, UserRef onBehalfOf,
                                  String decision, String comment, Instant createdAt, Instant decidedAt) {
    }

    public record ClaimDto(Long id, String code, String title, ClaimStatus status, BigDecimal total, UserRef employee,
                           DepartmentDto department, List<ClaimLineDto> lines, PolicyResultDto policyResult,
                           List<ApprovalStepDto> steps, SampleAudit sampleAudit, Instant createdAt, Instant submittedAt,
                           Instant decidedAt, long version) {
    }

    public record ClaimSummary(Long id, String code, String title, ClaimStatus status, BigDecimal total, int lineCount,
                               List<String> violationCodes, Instant submittedAt, Instant updatedAt) {
    }

    public record ClaimSummaryPage(List<ClaimSummary> items, int page, int size, long total) {
    }

    public record CommentDto(Long id, UserRef author, String text, Instant createdAt) {
    }
}
