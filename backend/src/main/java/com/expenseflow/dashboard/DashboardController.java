package com.expenseflow.dashboard;

import java.util.List;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.dashboard.KpiDtos.Kpis;
import com.expenseflow.security.JwtTokenService;

/** GET /api/dashboard/kpis?from&to[&departmentId] - any signed-in user; the scope follows the caller's roles. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final KpiService kpis;

    public DashboardController(KpiService kpis) {
        this.kpis = kpis;
    }

    @GetMapping("/kpis")
    public Kpis kpis(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @RequestParam(required = false) Long departmentId, @AuthenticationPrincipal Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList(JwtTokenService.ROLES_CLAIM);
        return kpis.kpis(DateRange.parse(from, to), Long.parseLong(jwt.getSubject()),
                roles == null ? List.of() : roles, departmentId);
    }
}
