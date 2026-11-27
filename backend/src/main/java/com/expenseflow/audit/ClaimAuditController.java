package com.expenseflow.audit;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.audit.ClaimAuditTimelineService.AuditEventView;

/** FR-13 claim timeline. Roles per rbac.md (SUPER_ADMIN uses /api/admin/audit instead); row rule in the service. */
@RestController
class ClaimAuditController {

    private final ClaimAuditTimelineService timeline;

    ClaimAuditController(ClaimAuditTimelineService timeline) {
        this.timeline = timeline;
    }

    @GetMapping("/api/claims/{id}/audit")
    @PreAuthorize("hasAnyRole('EMPLOYEE', 'MANAGER', 'FINANCE_AUDITOR', 'FINANCE_HEAD')")
    List<AuditEventView> audit(@PathVariable Long id, Authentication auth) {
        Set<String> roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a.startsWith("ROLE_"))
                .map(a -> a.substring("ROLE_".length()))
                .collect(Collectors.toSet());
        return timeline.timeline(id, AuditService.currentUserId(), roles);
    }
}
