package com.expenseflow.admin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.expenseflow.admin.AdminDtos.CreateUserRequest;
import com.expenseflow.admin.AdminDtos.Page;
import com.expenseflow.admin.AdminDtos.PatchUserRequest;
import com.expenseflow.api.ApiException;
import com.expenseflow.api.ErrorCodes;
import com.expenseflow.audit.AuditService;
import com.expenseflow.domain.Department;
import com.expenseflow.domain.Role;
import com.expenseflow.domain.User;
import com.expenseflow.repository.DepartmentRepository;
import com.expenseflow.repository.RoleRepository;
import com.expenseflow.repository.UserRepository;
import com.expenseflow.security.AuthDtos.UserView;

/**
 * FR-15 user administration. Every change writes an audit event in the same transaction. An admin can never
 * take away their own SUPER_ADMIN (by role change or by disabling themselves): 409 CANNOT_REMOVE_OWN_ADMIN.
 */
@Service
public class AdminUserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final DepartmentRepository departments;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final EntityManager em;

    public AdminUserService(UserRepository users, RoleRepository roles, DepartmentRepository departments,
            PasswordEncoder passwordEncoder, AuditService audit, EntityManager em) {
        this.users = users;
        this.roles = roles;
        this.departments = departments;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.em = em;
    }

    @Transactional(readOnly = true)
    public Page<UserView> list(String q, String role, int page, int size) {
        StringBuilder where = new StringBuilder(" where 1 = 1");
        Map<String, Object> params = new HashMap<>();
        if (q != null && !q.isBlank()) {
            where.append(" and (lower(u.name) like :q or lower(u.email) like :q or lower(u.employeeCode) like :q)");
            params.put("q", "%" + q.trim().toLowerCase() + "%");
        }
        if (role != null && !role.isBlank()) {
            String code = requireRole(role.trim()).getCode();
            where.append(" and exists (select 1 from User u2 join u2.roles r where u2 = u and r.code = :role)");
            params.put("role", code);
        }
        TypedQuery<User> items = em.createQuery("select u from User u" + where + " order by u.id", User.class);
        TypedQuery<Long> count = em.createQuery("select count(u) from User u" + where, Long.class);
        params.forEach((k, v) -> {
            items.setParameter(k, v);
            count.setParameter(k, v);
        });
        List<UserView> views = items.setFirstResult(page * size).setMaxResults(size).getResultList()
                .stream().map(UserView::of).toList();
        return new Page<>(views, page, size, count.getSingleResult());
    }

    @Transactional
    public UserView create(CreateUserRequest req) {
        if (users.existsByEmailIgnoreCase(req.email().trim())) {
            throw emailTaken();
        }
        User user = new User(req.name().trim(), req.email(), passwordEncoder.encode(req.password()));
        user.getRoles().addAll(resolveRoles(req.roles()));
        if (req.departmentId() != null) {
            user.setDepartment(requireDepartment(req.departmentId()));
        }
        if (req.managerId() != null) {
            user.setManager(requireUser(req.managerId(), "managerId"));
        }
        try {
            users.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            throw emailTaken();
        }
        audit.record("USER_CREATED", AuditService.USER, user.getId(), null, snapshot(user));
        return UserView.of(user);
    }

    @Transactional
    public UserView replaceRoles(long id, List<String> requested, long actingAdminId) {
        User user = requireUser(id, "id");
        Set<Role> newRoles = resolveRoles(requested);
        if (id == actingAdminId && newRoles.stream().noneMatch(r -> r.getCode().equals(Role.SUPER_ADMIN))) {
            throw cannotRemoveOwnAdmin();
        }
        Map<String, Object> before = snapshot(user);
        user.getRoles().clear();
        user.getRoles().addAll(newRoles);
        users.saveAndFlush(user);
        audit.record("USER_ROLES_CHANGED", AuditService.USER, user.getId(), before, snapshot(user));
        return UserView.of(user);
    }

    @Transactional
    public UserView patch(long id, PatchUserRequest req, long actingAdminId) {
        User user = requireUser(id, "id");
        if (id == actingAdminId && Boolean.FALSE.equals(req.enabled())) {
            throw cannotRemoveOwnAdmin();
        }
        Map<String, Object> before = snapshot(user);
        if (req.enabled() != null) {
            user.setEnabled(req.enabled());
        }
        if (req.managerId() != null) {
            if (req.managerId() == id) {
                throw badRequest("A user cannot be their own manager");
            }
            user.setManager(requireUser(req.managerId(), "managerId"));
        }
        if (req.departmentId() != null) {
            user.setDepartment(requireDepartment(req.departmentId()));
        }
        users.saveAndFlush(user);
        String action = req.enabled() == null ? "USER_UPDATED" : req.enabled() ? "USER_ENABLED" : "USER_DISABLED";
        audit.record(action, AuditService.USER, user.getId(), before, snapshot(user));
        return UserView.of(user);
    }

    /** Small, password-free snapshot for the audit trail. */
    private static Map<String, Object> snapshot(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("email", u.getEmail());
        m.put("roles", u.roleCodes().stream().sorted().toList());
        m.put("enabled", u.isEnabled());
        m.put("departmentId", u.getDepartment() == null ? null : u.getDepartment().getId());
        m.put("managerId", u.getManager() == null ? null : u.getManager().getId());
        return m;
    }

    private Set<Role> resolveRoles(List<String> codes) {
        Set<Role> out = new LinkedHashSet<>();
        for (String code : codes) {
            out.add(requireRole(code));
        }
        return out;
    }

    private Role requireRole(String code) {
        return roles.findById(code == null ? "" : code)
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED,
                        "Unknown role " + code, Map.of("allowed", new ArrayList<>(List.of(Role.EMPLOYEE, Role.MANAGER,
                                Role.FINANCE_AUDITOR, Role.FINANCE_HEAD, Role.SUPER_ADMIN)))));
    }

    private User requireUser(long id, String field) {
        return users.findById(id).orElseThrow(() -> "id".equals(field)
                ? ApiException.notFound(ErrorCodes.NOT_FOUND, "User not found")
                : badRequest(field + " does not exist"));
    }

    private Department requireDepartment(long id) {
        return departments.findById(id).orElseThrow(() -> badRequest("departmentId does not exist"));
    }

    private static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, ErrorCodes.VALIDATION_FAILED, message, null);
    }

    private static ApiException emailTaken() {
        return ApiException.conflict(ErrorCodes.EMAIL_TAKEN, "An account with this email already exists");
    }

    private static ApiException cannotRemoveOwnAdmin() {
        return ApiException.conflict(ErrorCodes.CANNOT_REMOVE_OWN_ADMIN,
                "You cannot remove your own SUPER_ADMIN role or disable your own account");
    }
}
