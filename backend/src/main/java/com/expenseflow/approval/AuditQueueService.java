package com.expenseflow.approval;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.api.ApiException;
import com.expenseflow.api.ErrorCodes;
import com.expenseflow.audit.AuditService;
import com.expenseflow.domain.ApprovalDecision;
import com.expenseflow.domain.ApprovalLevel;
import com.expenseflow.domain.AuditActions;
import com.expenseflow.domain.Claim;
import com.expenseflow.domain.ClaimStatus;
import com.expenseflow.domain.SampleAudit;
import com.expenseflow.domain.User;
import com.expenseflow.repository.ClaimRepository;

/**
 * FR-9 / FR-10 auditor queue: claims waiting at the AUDITOR level for me (assigned or delegated to me) plus
 * AUTO_APPROVED claims picked for a sample audit (BR-13). Never the caller's own claims. Filters, sort and paging
 * run in SQL. Bulk approve delegates every item to {@link ApprovalService#decide} in its own transaction, so one
 * failing item never rolls back the others and all chain rules (assignment, self-approval, state) still apply.
 */
@Service
public class AuditQueueService {

    public static final int MAX_BULK = 100;

    public record Filters(Long departmentId, String category, BigDecimal minAmount, BigDecimal maxAmount,
            String violation, boolean sampleOnly, Integer olderThanHours, String sort) {
    }

    /** One queue row; {@code level} is null for a sample audit. */
    public record QueueItem(Claim claim, ApprovalLevel level, User onBehalfOf) {
    }

    public record QueuePage(List<QueueItem> items, long total) {
    }

    public record BulkResult(long claimId, boolean ok, String code, ClaimStatus status) {
    }

    private static final Map<String, String> SORTS = Map.of(
            "submittedAt", "c.submitted_at asc, c.id asc",
            "-submittedAt", "c.submitted_at desc, c.id desc",
            "total", "c.total_amount asc, c.id asc",
            "-total", "c.total_amount desc, c.id desc");

    private final ApprovalService approvals;
    private final ClaimRepository claims;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditService audit;

    public AuditQueueService(ApprovalService approvals, ClaimRepository claims, NamedParameterJdbcTemplate jdbc,
            AuditService audit) {
        this.approvals = approvals;
        this.claims = claims;
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public QueuePage queue(long me, Filters f, int page, int size) {
        String order = SORTS.get(f.sort() == null || f.sort().isBlank() ? "submittedAt" : f.sort().trim());
        if (order == null) {
            throw badRequest("sort must be one of submittedAt, -submittedAt, total, -total");
        }
        Map<Long, ApprovalService.InboxItem> mine = new LinkedHashMap<>();
        for (ApprovalService.InboxItem i : approvals.inbox(me)) {
            if (i.step().getLevel() == ApprovalLevel.AUDITOR) {
                mine.put(i.step().getClaim().getId(), i);
            }
        }
        MapSqlParameterSource p = new MapSqlParameterSource("me", me)
                .addValue("assigned", mine.isEmpty() ? List.of(-1L) : List.copyOf(mine.keySet()));
        StringBuilder where = new StringBuilder(" from claims c join users e on e.id = c.employee_id"
                + " where c.employee_id <> :me and (");
        if (!f.sampleOnly()) {
            where.append("(c.status = 'PENDING_AUDITOR' and c.id in (:assigned)) or ");
        }
        where.append("c.sample_audit = 'PENDING')");
        if (f.departmentId() != null) {
            where.append(" and e.department_id = :dept");
            p.addValue("dept", f.departmentId());
        }
        if (f.minAmount() != null) {
            where.append(" and c.total_amount >= :min");
            p.addValue("min", f.minAmount());
        }
        if (f.maxAmount() != null) {
            where.append(" and c.total_amount <= :max");
            p.addValue("max", f.maxAmount());
        }
        if (f.olderThanHours() != null) {
            where.append(" and c.submitted_at <= :olderThan");
            p.addValue("olderThan", Timestamp.from(Instant.now().minus(Duration.ofHours(Math.max(0, f.olderThanHours())))));
        }
        if (f.category() != null && !f.category().isBlank()) {
            where.append(" and exists (select 1 from claim_lines l where l.claim_id = c.id and l.category = :cat)");
            p.addValue("cat", f.category().trim().toUpperCase());
        }
        if (f.violation() != null && !f.violation().isBlank()) {
            where.append(" and exists (select 1 from claim_lines l cross join lateral jsonb_array_elements("
                    + "case when jsonb_typeof(l.violations) = 'array' then l.violations else '[]'::jsonb end) v(item)"
                    + " where l.claim_id = c.id and coalesce(item->>'code', item->>'rule') = :violation)");
            p.addValue("violation", f.violation().trim().toUpperCase());
        }
        long total = jdbc.queryForObject("select count(*)" + where, p, Long.class);
        p.addValue("limit", size).addValue("offset", (long) page * size);
        List<Long> ids = jdbc.queryForList("select c.id" + where + " order by " + order
                + " limit :limit offset :offset", p, Long.class);

        Map<Long, Claim> byId = claims.findAllById(ids).stream().collect(Collectors.toMap(Claim::getId, Function.identity()));
        List<QueueItem> items = new ArrayList<>();
        for (Long id : ids) {
            Claim c = byId.get(id);
            ApprovalService.InboxItem i = mine.get(id);
            boolean pendingStep = i != null && c.getStatus() == ClaimStatus.PENDING_AUDITOR;
            items.add(new QueueItem(c, pendingStep ? ApprovalLevel.AUDITOR : null, pendingStep ? i.onBehalfOf() : null));
        }
        return new QueuePage(items, total);
    }

    /** Not transactional on purpose: every item runs in its own transaction inside ApprovalService.decide. */
    public List<BulkResult> bulkApprove(long me, List<Long> claimIds, String comment) {
        if (claimIds == null || claimIds.isEmpty() || claimIds.size() > MAX_BULK) {
            throw badRequest("claimIds must contain 1.." + MAX_BULK + " ids");
        }
        List<BulkResult> results = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(claimIds)) {
            try {
                Claim c = approvals.decide(me, id, ApprovalDecision.APPROVED, comment);
                results.add(new BulkResult(id, true, null, c.getStatus()));
            } catch (ApiException e) {
                results.add(new BulkResult(id, false, e.code(), null));
            } catch (OptimisticLockingFailureException e) {
                results.add(new BulkResult(id, false, ErrorCodes.CONCURRENT_MODIFICATION, null));
            }
        }
        return results;
    }

    /** FR-10: OK closes the sample; EXCEPTION rejects the claim (reason SAMPLE_AUDIT_EXCEPTION). */
    @Transactional
    public Claim sampleAudit(long me, long claimId, String result, String comment) {
        Claim c = claims.findByIdForUpdate(claimId)
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Claim not found"));
        if (c.isOwnedBy(me)) {
            throw new ApiException(HttpStatus.FORBIDDEN, ErrorCodes.SELF_APPROVAL_FORBIDDEN,
                    "You cannot audit your own claim", null);
        }
        if (c.getSampleAudit() != SampleAudit.PENDING) {
            throw ApiException.conflict(ErrorCodes.ILLEGAL_STATE_TRANSITION,
                    "Claim has no pending sample audit (sampleAudit=" + c.getSampleAudit() + ")");
        }
        boolean exception = "EXCEPTION".equalsIgnoreCase(result);
        if (!exception && !"OK".equalsIgnoreCase(result)) {
            throw badRequest("result must be OK or EXCEPTION");
        }
        Map<String, Object> before = new HashMap<>(Map.of("status", c.getStatus().name(), "sampleAudit", "PENDING"));
        Map<String, Object> after = new LinkedHashMap<>();
        if (exception) {
            if (comment == null || comment.trim().length() < 3) {
                throw badRequest("comment (at least 3 characters) is required for EXCEPTION");
            }
            if (c.getStatus() != ClaimStatus.APPROVED) {
                throw ApiException.conflict(ErrorCodes.ILLEGAL_STATE_TRANSITION,
                        "Claim is " + c.getStatus() + "; only an APPROVED claim can be rejected by a sample audit");
            }
            c.setSampleAudit(SampleAudit.EXCEPTION);
            c.setStatus(ClaimStatus.REJECTED);
            c.setDecidedAt(Instant.now());
            after.put("reason", "SAMPLE_AUDIT_EXCEPTION");
        } else {
            c.setSampleAudit(SampleAudit.OK);
        }
        after.put("status", c.getStatus().name());
        after.put("sampleAudit", c.getSampleAudit().name());
        if (comment != null && !comment.isBlank()) after.put("comment", comment.trim());
        audit.record(exception ? AuditActions.CLAIM_SAMPLE_AUDIT_EXCEPTION : AuditActions.CLAIM_SAMPLE_AUDIT_OK,
                AuditService.CLAIM, c.getId(), before, after);
        return claims.saveAndFlush(c);
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, message, null);
    }
}
