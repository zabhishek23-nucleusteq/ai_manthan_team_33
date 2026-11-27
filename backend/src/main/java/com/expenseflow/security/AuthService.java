package com.expenseflow.security;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.api.ApiException;
import com.expenseflow.domain.Department;
import com.expenseflow.domain.Role;
import com.expenseflow.domain.User;
import com.expenseflow.repository.DepartmentRepository;
import com.expenseflow.repository.RoleRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.AuthDtos.LoginRequest;
import com.expenseflow.security.AuthDtos.LoginResponse;
import com.expenseflow.security.AuthDtos.SignupRequest;
import com.expenseflow.security.AuthDtos.UserView;

/**
 * FR-1: self sign-up (always EMPLOYEE), login with BCrypt check returning a JWT, current user.
 * Login failures never reveal whether the email exists.
 */
@Service
public class AuthService {

    static final String INVALID_CREDENTIALS = "INVALID_CREDENTIALS";

    private final UserRepository users;
    private final RoleRepository roles;
    private final DepartmentRepository departments;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenService tokens;
    /** Compared against when the email is unknown, so both paths cost one BCrypt check (no timing oracle). */
    private final String dummyHash;

    public AuthService(UserRepository users, RoleRepository roles, DepartmentRepository departments,
            PasswordEncoder passwordEncoder, JwtTokenService tokens) {
        this.users = users;
        this.roles = roles;
        this.departments = departments;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.dummyHash = passwordEncoder.encode("dummy-password-for-timing");
    }

    @Transactional
    public UserView signup(SignupRequest req) {
        if (users.existsByEmailIgnoreCase(req.email().trim())) {
            throw emailTaken();
        }
        User user = new User(req.name().trim(), req.email(), passwordEncoder.encode(req.password()));
        if (req.departmentId() != null) {
            Department dept = departments.findById(req.departmentId()).orElseThrow(() -> new ApiException(
                    HttpStatus.BAD_REQUEST, "UNKNOWN_DEPARTMENT", "Department does not exist", null));
            user.setDepartment(dept);
        }
        user.getRoles().add(roles.findById(Role.EMPLOYEE)
                .orElseThrow(() -> new IllegalStateException("Role EMPLOYEE missing (V2__identity.sql)")));
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // concurrent sign-up with the same email hit the unique index
            throw emailTaken();
        }
        return UserView.of(user);
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest req) {
        User user = users.findByEmailIgnoreCase(req.email().trim()).orElse(null);
        boolean passwordOk = passwordEncoder.matches(req.password(), user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !passwordOk) {
            throw unauthorized(INVALID_CREDENTIALS, "Email or password is incorrect");
        }
        if (!user.isEnabled()) {
            throw unauthorized("ACCOUNT_DISABLED", "This account is disabled");
        }
        JwtTokenService.IssuedToken token = tokens.issue(user.getId(), user.getEmail(), user.roleCodes());
        return new LoginResponse(token.value(), "Bearer", token.expiresInSeconds(), UserView.of(user));
    }

    /** The user behind a valid token; 401 if it was deleted or disabled after the token was issued. */
    @Transactional(readOnly = true)
    public UserView me(String subject) {
        long id;
        try {
            id = Long.parseLong(subject);
        } catch (NumberFormatException e) {
            throw unauthorized("UNAUTHENTICATED", "Authentication required");
        }
        return users.findById(id)
                .filter(User::isEnabled)
                .map(UserView::of)
                .orElseThrow(() -> unauthorized("UNAUTHENTICATED", "Authentication required"));
    }

    private static ApiException emailTaken() {
        return ApiException.conflict("EMAIL_TAKEN", "An account with this email already exists");
    }

    private static ApiException unauthorized(String code, String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, code, message, null);
    }
}
