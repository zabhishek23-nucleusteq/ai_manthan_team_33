package com.aimanthan.expenseflow.security;

import java.util.Map;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only endpoints (src/test, never shipped) to exercise the security chain before real role-protected
 * endpoints exist. Picked up by component scanning in every test context, so it adds no extra context.
 */
@RestController
class SecurityProbeController {

    @GetMapping("/api/test-probe/authenticated")
    Map<String, Object> authenticated(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("sub", jwt.getSubject(), "roles", jwt.getClaimAsStringList(JwtTokenService.ROLES_CLAIM));
    }

    @GetMapping("/api/test-probe/super-admin")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    Map<String, Object> superAdminOnly() {
        return Map.of("ok", true);
    }
}
