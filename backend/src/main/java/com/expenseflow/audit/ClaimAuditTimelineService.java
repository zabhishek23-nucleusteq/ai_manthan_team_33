package com.expenseflow.audit;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.api.ApiException;
import com.expenseflow.api.ErrorCodes;
import com.expenseflow.domain.Claim;
import com.expenseflow.domain.Role;
import com.expenseflow.domain.User;
import com.expenseflow.repository.ApprovalStepRepository;
import com.expenseflow.repository.ClaimRepository;
import com.expenseflow.repository.UserRepository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /api/claims/{id}/audit} (FR-13). Row rule (rbac.md): the claim owner, any approver on any step of
 * the claim (assignee, delegate who acted, or the person a delegate acted for), FINANCE_AUDITOR and
 * FINANCE_HEAD. Everyone else gets 404 - the same answer as a claim that does not exist (no enumeration).
 */
@Service
public class ClaimAuditTimelineService {

    public record UserRef(Long id, String name, String email) {
    }

    public record AuditEventView(Long id, java.time.Instant at, UserRef actor, UserRef onBehalfOf, String action,
                                 String entityType, String entityId, JsonNode before, JsonNode after) {
    }

    private final ClaimRepository claims;
    private final ApprovalStepRepository steps;
    private final AuditEventRepository events;
    private final UserRepository users;
    private final JsonMapper json;

    public ClaimAuditTimelineService(ClaimRepository claims, ApprovalStepRepository steps, AuditEventRepository events,
                                     UserRepository users, JsonMapper json) {
        this.claims = claims;
        this.steps = steps;
        this.events = events;
        this.users = users;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<AuditEventView> timeline(Long claimId, Long userId, Collection<String> roles) {
        Claim claim = claims.findById(claimId).orElseThrow(ClaimAuditTimelineService::notFound);
        if (!canSee(claim, userId, roles)) {
            throw notFound();
        }
        List<AuditEvent> rows = events.findByEntityTypeAndEntityIdOrderByAtAscIdAsc(AuditService.CLAIM, claimId.toString());
        List<Long> userIds = rows.stream()
                .flatMap(e -> Stream.of(e.getActorId(), e.getOnBehalfOfId()))
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, UserRef> refs = users.findAllById(userIds).stream()
                .collect(Collectors.toMap(User::getId, u -> new UserRef(u.getId(), u.getName(), u.getEmail()), (a, b) -> a));
        Function<Long, UserRef> ref = id -> id == null ? null : refs.get(id);
        return rows.stream()
                .map(e -> new AuditEventView(e.getId(), e.getAt(), ref.apply(e.getActorId()), ref.apply(e.getOnBehalfOfId()),
                        e.getAction(), e.getEntityType(), e.getEntityId(), parse(e.getBefore()), parse(e.getAfter())))
                .toList();
    }

    private boolean canSee(Claim claim, Long userId, Collection<String> roles) {
        if (roles.contains(Role.FINANCE_AUDITOR) || roles.contains(Role.FINANCE_HEAD)) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        if (claim.isOwnedBy(userId)) {
            return true;
        }
        return steps.findByClaimIdOrderByRoundAscIdAsc(claim.getId()).stream()
                .anyMatch(s -> isUser(s.getAssignee(), userId) || isUser(s.getActedBy(), userId)
                        || isUser(s.getOnBehalfOf(), userId));
    }

    private static boolean isUser(User user, Long userId) {
        return user != null && userId.equals(user.getId());
    }

    private JsonNode parse(String value) {
        return value == null ? null : json.readTree(value);
    }

    private static ApiException notFound() {
        return ApiException.notFound(ErrorCodes.NOT_FOUND, "Claim not found");
    }
}
