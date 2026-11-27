package com.expenseflow.approval;

import com.expenseflow.domain.ApprovalLevel;
import com.expenseflow.domain.Delegation;
import com.expenseflow.domain.Role;
import com.expenseflow.domain.User;
import com.expenseflow.repository.DelegationRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Who approves what (ADR-001 assignee resolution, BR-14). Read-only queries over users, roles and delegations,
 * kept inside the approval package so other packages need no changes.
 */
@Component
public class ApproverDirectory {

    static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    static final List<String> APPROVER_ROLES = List.of(Role.MANAGER, Role.FINANCE_AUDITOR, Role.FINANCE_HEAD);

    private final EntityManager em;
    private final DelegationRepository delegations;

    public ApproverDirectory(EntityManager em, DelegationRepository delegations) {
        this.em = em;
        this.delegations = delegations;
    }

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    /** Rank of a user's highest approver role: 0 none, 1 MANAGER, 2 FINANCE_AUDITOR, 3 FINANCE_HEAD. */
    static int rank(Set<String> roles) {
        if (roles.contains(Role.FINANCE_HEAD)) return 3;
        if (roles.contains(Role.FINANCE_AUDITOR)) return 2;
        if (roles.contains(Role.MANAGER)) return 1;
        return 0;
    }

    List<User> enabledWithRole(String role) {
        return em.createQuery("select distinct u from User u join u.roles r where r.code = :role and u.enabled = true order by u.id", User.class)
                .setParameter("role", role).getResultList();
    }

    List<User> approvers(String q) {
        String like = q == null || q.isBlank() ? "%" : "%" + q.trim().toLowerCase() + "%";
        return em.createQuery("""
                        select distinct u from User u join u.roles r where r.code in :roles and u.enabled = true
                          and (lower(u.name) like :q or lower(u.email) like :q) order by u.name""", User.class)
                .setParameter("roles", APPROVER_ROLES).setParameter("q", like).setMaxResults(50).getResultList();
    }

    private long openSteps(Long userId) {
        return em.createQuery("select count(s) from ApprovalStep s where s.assignee.id = :u and s.decision is null", Long.class)
                .setParameter("u", userId).getSingleResult();
    }

    /** The natural approver for a level, never the claimant; empty if none exists (caller escalates, BR-15). */
    Optional<User> resolve(ApprovalLevel level, User claimant) {
        return switch (level) {
            case MANAGER -> Optional.ofNullable(claimant.getManager())
                    .filter(m -> m.isEnabled() && !m.getId().equals(claimant.getId()));
            case AUDITOR -> enabledWithRole(Role.FINANCE_AUDITOR).stream()
                    .filter(u -> !u.getId().equals(claimant.getId()))
                    .min(Comparator.comparingLong((User u) -> openSteps(u.getId())).thenComparing(User::getId));
            case HEAD -> enabledWithRole(Role.FINANCE_HEAD).stream()
                    .filter(u -> !u.getId().equals(claimant.getId())).findFirst();
        };
    }

    /** Active delegate of an approver today (one hop, BR-14), if any. */
    Optional<User> activeDelegate(Long approverId) {
        return delegations.findActiveFor(approverId, today()).stream().findFirst().map(Delegation::getDelegate);
    }

    /** Approvers who delegated to this user today (the delegate acts for them). */
    List<Long> delegatorsOf(Long delegateId) {
        return em.createQuery("""
                        select d.delegator.id from Delegation d where d.delegate.id = :u and d.active = true
                          and :today between d.fromDate and d.toDate""", Long.class)
                .setParameter("u", delegateId).setParameter("today", today()).getResultList();
    }

    boolean hasActiveOverlap(Long userId, boolean asDelegator, LocalDate from, LocalDate to) {
        String side = asDelegator ? "d.delegator.id" : "d.delegate.id";
        return em.createQuery("select count(d) from Delegation d where " + side + " = :u and d.active = true"
                        + " and d.fromDate <= :to and d.toDate >= :from", Long.class)
                .setParameter("u", userId).setParameter("from", from).setParameter("to", to).getSingleResult() > 0;
    }
}
