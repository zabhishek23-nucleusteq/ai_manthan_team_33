-- Identity and organisation (T-012, L2). Auth logic (signup/login/JWT/BCrypt) is L3's T-004 on top of these tables.
-- employee_code / bank_account_no / ifsc feed the payout bank file (BR-17).

CREATE TABLE departments (
    id          BIGSERIAL    PRIMARY KEY,
    code        VARCHAR(20)  NOT NULL UNIQUE,
    name        VARCHAR(100) NOT NULL UNIQUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE users (
    id               BIGSERIAL    PRIMARY KEY,
    employee_code    VARCHAR(20)  UNIQUE,
    name             VARCHAR(120) NOT NULL,
    email            VARCHAR(160) NOT NULL,
    password_hash    VARCHAR(100) NOT NULL,
    department_id    BIGINT       REFERENCES departments(id),
    manager_id       BIGINT       REFERENCES users(id),
    bank_account_no  VARCHAR(34),
    ifsc             VARCHAR(11),
    enabled          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT users_not_own_manager CHECK (manager_id IS NULL OR manager_id <> id)
);
-- emails are compared case-insensitively
CREATE UNIQUE INDEX users_email_lower_uq ON users (lower(email));
CREATE INDEX users_manager_idx ON users (manager_id);
CREATE INDEX users_department_idx ON users (department_id);

CREATE TABLE roles (
    code         VARCHAR(30)  PRIMARY KEY,
    description  VARCHAR(200) NOT NULL
);

CREATE TABLE user_roles (
    user_id    BIGINT      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_code  VARCHAR(30) NOT NULL REFERENCES roles(code),
    PRIMARY KEY (user_id, role_code)
);

-- Roles are reference data (docs/architecture/rbac.md section 1)
INSERT INTO roles (code, description) VALUES
    ('EMPLOYEE',        'Files own expense claims (self sign-up role)'),
    ('MANAGER',         'Approves claims of direct reports and delegated claims'),
    ('FINANCE_AUDITOR', 'Level-2 approver above 25,000 INR, audit queue, sample audits'),
    ('FINANCE_HEAD',    'Level-3 approver above 1,00,000 INR, payout batches, policy rules'),
    ('SUPER_ADMIN',     'Users and roles, system overview, audit log, health');
