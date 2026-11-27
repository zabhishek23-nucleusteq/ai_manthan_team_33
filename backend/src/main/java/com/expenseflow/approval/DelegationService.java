package com.expenseflow.approval;

import com.expenseflow.api.ApiException;
import com.expenseflow.api.ErrorCodes;
import com.expenseflow.domain.AuditActions;
import com.expenseflow.domain.Delegation;
import com.expenseflow.domain.User;
import com.expenseflow.repository.DelegationRepository;
import com.expenseflow.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Delegations (FR-8, BR-14): same-or-higher approver role, never yourself, one hop (the delegate must not be away
 * themselves and you must not already be delegating in that period), max 30 days, not ending in the past.
 * Open steps follow automatically: an active delegate sees and may act on the delegator's open steps.
 */
@Service
public class DelegationService {

    private final DelegationRepository delegations;
    private final UserRepository users;
    private final ApproverDirectory directory;
    private final ApplicationEventPublisher events;

    public DelegationService(DelegationRepository delegations, UserRepository users, ApproverDirectory directory,
                             ApplicationEventPublisher events) {
        this.delegations = delegations;
        this.users = users;
        this.directory = directory;
        this.events = events;
    }

    private static ApiException invalid(String reason, String message) {
        return ApiException.refused(ErrorCodes.DELEGATION_INVALID, message, Map.of("reason", reason));
    }

    @Transactional(readOnly = true)
    public List<Delegation> mine(long userId) {
        List<Delegation> out = new ArrayList<>(delegations.findByDelegatorIdOrderByFromDateDesc(userId));
        out.addAll(delegations.findAll().stream().filter(d -> d.getDelegate().getId().equals(userId)).toList());
        out.sort(Comparator.comparing(Delegation::getFromDate).reversed().thenComparing(Delegation::getId));
        return out;
    }

    @Transactional
    public Delegation create(long userId, long delegateId, LocalDate from, LocalDate to) {
        User me = users.findById(userId).orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "User not found"));
        if (delegateId == userId) throw invalid("SELF", "You cannot delegate to yourself");
        User delegate = users.findById(delegateId).filter(User::isEnabled)
                .orElseThrow(() -> invalid("UNKNOWN_DELEGATE", "Delegate not found"));
        int myRank = ApproverDirectory.rank(me.roleCodes());
        if (myRank == 0) throw invalid("NOT_APPROVER", "Only approvers can delegate");
        if (ApproverDirectory.rank(delegate.roleCodes()) < myRank) {
            throw invalid("ROLE_TOO_LOW", "The delegate needs the same or a higher approver role");
        }
        if (to.isBefore(from)) throw invalid("DATES", "'to' must not be before 'from'");
        if (ChronoUnit.DAYS.between(from, to) > Delegation.MAX_DAYS) throw invalid("TOO_LONG", "A delegation lasts at most 30 days");
        if (to.isBefore(ApproverDirectory.today())) throw invalid("IN_PAST", "The delegation period is already over");
        if (directory.hasActiveOverlap(delegateId, true, from, to)) {
            throw invalid("CHAIN", "The delegate has delegated their own approvals in that period (one hop only)");
        }
        if (directory.hasActiveOverlap(userId, true, from, to)) {
            throw invalid("OVERLAP", "You already have an active delegation in that period");
        }
        if (directory.hasActiveOverlap(userId, false, from, to)) {
            throw invalid("CHAIN", "You act as a delegate in that period and cannot pass it on (one hop only)");
        }
        Delegation d = delegations.saveAndFlush(new Delegation(me, delegate, from, to, null));
        events.publishEvent(new ApprovalEvents.DelegationChanged(AuditActions.DELEGATION_CREATED, d.getId(), userId, null,
                Map.of("delegatorId", userId, "delegateId", delegateId, "from", from.toString(), "to", to.toString())));
        return d;
    }

    @Transactional
    public void revoke(long userId, long id) {
        Delegation d = delegations.findById(id).filter(x -> x.getDelegator().getId().equals(userId))
                .orElseThrow(() -> ApiException.notFound(ErrorCodes.NOT_FOUND, "Delegation not found"));
        if (!d.isActive()) return;
        d.setActive(false);
        delegations.saveAndFlush(d);
        events.publishEvent(new ApprovalEvents.DelegationChanged(ApprovalEvents.DELEGATION_REVOKED, d.getId(), userId,
                Map.of("active", true), Map.of("active", false)));
    }
}
