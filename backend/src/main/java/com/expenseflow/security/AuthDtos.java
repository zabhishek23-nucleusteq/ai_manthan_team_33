package com.expenseflow.security;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import com.expenseflow.domain.User;

/** Request/response shapes of /api/auth/** (contracts/openapi.yaml: SignupRequest, LoginRequest, LoginResponse, User). */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record SignupRequest(
            @NotBlank @Size(min = 2, max = 100) String name,
            @NotBlank @Email @Size(max = 160) String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            Long departmentId) {
    }

    public record LoginRequest(@NotBlank @Email String email, @NotBlank String password) {
    }

    public record LoginResponse(String accessToken, String tokenType, long expiresIn, UserView user) {
    }

    public record DepartmentRef(Long id, String name) {
    }

    public record UserRef(Long id, String name, String email) {
    }

    public record UserView(Long id, String name, String email, String employeeCode, List<String> roles,
            DepartmentRef department, UserRef manager, boolean enabled) {

        /** Call inside a transaction: touches the lazy department / manager. */
        public static UserView of(User u) {
            DepartmentRef dept = u.getDepartment() == null ? null
                    : new DepartmentRef(u.getDepartment().getId(), u.getDepartment().getName());
            UserRef mgr = u.getManager() == null ? null
                    : new UserRef(u.getManager().getId(), u.getManager().getName(), u.getManager().getEmail());
            return new UserView(u.getId(), u.getName(), u.getEmail(), u.getEmployeeCode(),
                    u.roleCodes().stream().sorted().toList(), dept, mgr, u.isEnabled());
        }
    }
}
