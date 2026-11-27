package com.expenseflow.admin;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import com.expenseflow.security.AuthDtos.UserRef;

/** Shapes of /api/admin/** (contracts/openapi.yaml, tag admin). */
public final class AdminDtos {

    private AdminDtos() {
    }

    public record CreateUserRequest(
            @NotBlank @Size(min = 2, max = 100) String name,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            @NotEmpty List<String> roles,
            Long departmentId,
            Long managerId) {
    }

    public record RolesRequest(@NotEmpty List<String> roles) {
    }

    /** Only the fields present are changed. */
    public record PatchUserRequest(Boolean enabled, Long managerId, Long departmentId) {
    }

    public record Page<T>(List<T> items, int page, int size, long total) {
    }

    public record AuditEventView(Long id, Instant at, UserRef actor, UserRef onBehalfOf, String action,
            String entityType, String entityId, Object before, Object after) {
    }

    public record Range(Instant from, Instant to) {
    }

    /** System-wide user statistics. KPI totals/series (Kpis schema) are added by the dashboard task T-027. */
    public record Overview(Range range, String scope, Map<String, Long> usersByRole, long activeUsers,
            long totalUsers, long newUsersInRange) {
    }

    public record ServiceStatus(String name, String status, long latencyMs) {
    }

    public record Health(List<ServiceStatus> services, double errorRate5m, Double oldestPendingApprovalHours) {
    }
}
