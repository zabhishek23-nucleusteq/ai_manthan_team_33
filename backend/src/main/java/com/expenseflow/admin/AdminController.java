package com.expenseflow.admin;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.expenseflow.admin.AdminDtos.AuditEventView;
import com.expenseflow.admin.AdminDtos.CreateUserRequest;
import com.expenseflow.admin.AdminDtos.Health;
import com.expenseflow.admin.AdminDtos.Overview;
import com.expenseflow.admin.AdminDtos.Page;
import com.expenseflow.admin.AdminDtos.PatchUserRequest;
import com.expenseflow.admin.AdminDtos.RolesRequest;
import com.expenseflow.dashboard.DateRange;
import com.expenseflow.security.AuthDtos.UserView;

/** Super admin console APIs (FR-15). Every endpoint requires SUPER_ADMIN. */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminController {

    private final AdminUserService userAdmin;
    private final AdminInsightsService insights;

    public AdminController(AdminUserService userAdmin, AdminInsightsService insights) {
        this.userAdmin = userAdmin;
        this.insights = insights;
    }

    @GetMapping("/users")
    public Page<UserView> users(@RequestParam(required = false) String q, @RequestParam(required = false) String role,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return userAdmin.list(q, role, page(page), size(size));
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    public UserView createUser(@Valid @RequestBody CreateUserRequest req) {
        return userAdmin.create(req);
    }

    @PutMapping("/users/{id}/roles")
    public UserView replaceRoles(@PathVariable long id, @Valid @RequestBody RolesRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return userAdmin.replaceRoles(id, req.roles(), Long.parseLong(jwt.getSubject()));
    }

    @PatchMapping("/users/{id}")
    public UserView patchUser(@PathVariable long id, @RequestBody PatchUserRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        return userAdmin.patch(id, req, Long.parseLong(jwt.getSubject()));
    }

    @GetMapping("/audit")
    public Page<AuditEventView> audit(@RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Long actorId, @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return insights.audit(DateRange.parse(from, to), actorId, action, entityType, page(page), size(size));
    }

    @GetMapping("/overview")
    public Overview overview(@RequestParam(required = false) String from, @RequestParam(required = false) String to,
            @AuthenticationPrincipal Jwt jwt) {
        java.util.List<String> roles = jwt.getClaimAsStringList(com.expenseflow.security.JwtTokenService.ROLES_CLAIM);
        return insights.overview(DateRange.parse(from, to), Long.parseLong(jwt.getSubject()),
                roles == null ? java.util.List.of() : roles);
    }

    private static int page(int page) {
        return Math.max(0, page);
    }

    private static int size(int size) {
        return Math.min(100, Math.max(1, size));
    }

    @GetMapping("/health")
    public Health health() {
        return insights.health();
    }
}
