package com.expenseflow.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Reference data seeded by V2__identity.sql: EMPLOYEE, MANAGER, FINANCE_AUDITOR, FINANCE_HEAD, SUPER_ADMIN. */
@Entity
@Table(name = "roles")
public class Role {

    public static final String EMPLOYEE = "EMPLOYEE";
    public static final String MANAGER = "MANAGER";
    public static final String FINANCE_AUDITOR = "FINANCE_AUDITOR";
    public static final String FINANCE_HEAD = "FINANCE_HEAD";
    public static final String SUPER_ADMIN = "SUPER_ADMIN";

    @Id
    @Column(length = 30)
    private String code;

    @Column(nullable = false, length = 200)
    private String description;

    protected Role() {
    }

    public Role(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public String getCode() { return code; }
    public String getDescription() { return description; }
}
