package com.expenseflow.domain;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** An employee / staff user. Auth (BCrypt, JWT) is built on this entity by the security package (L3). */
@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_code", unique = true, length = 20)
    private String employeeCode;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 160)
    private String email;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id")
    private Department department;

    /** Line manager (reporting hierarchy). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "manager_id")
    private User manager;

    /** Synthetic payout bank details, used in the bank CSV (BR-17). */
    @Column(name = "bank_account_no", length = 34)
    private String bankAccountNo;

    @Column(length = 11)
    private String ifsc;

    @Column(nullable = false)
    private boolean enabled = true;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_code"))
    private Set<Role> roles = new HashSet<>();

    protected User() {
    }

    public User(String name, String email, String passwordHash) {
        this.name = name;
        this.email = email == null ? null : email.trim().toLowerCase();
        this.passwordHash = passwordHash;
    }

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    public boolean hasRole(String code) {
        return roles.stream().anyMatch(r -> r.getCode().equals(code));
    }

    public Set<String> roleCodes() {
        return roles.stream().map(Role::getCode).collect(Collectors.toUnmodifiableSet());
    }

    public Long getId() { return id; }
    public String getEmployeeCode() { return employeeCode; }
    public void setEmployeeCode(String employeeCode) { this.employeeCode = employeeCode; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }
    public User getManager() { return manager; }
    public void setManager(User manager) { this.manager = manager; }
    public String getBankAccountNo() { return bankAccountNo; }
    public void setBankAccountNo(String bankAccountNo) { this.bankAccountNo = bankAccountNo; }
    public String getIfsc() { return ifsc; }
    public void setIfsc(String ifsc) { this.ifsc = ifsc; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Instant getCreatedAt() { return createdAt; }
    /** For seed data with historical dates only. */
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Set<Role> getRoles() { return roles; }
}
