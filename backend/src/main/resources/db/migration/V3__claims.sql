-- Claims domain (T-012, L2). Follows docs/architecture/README.md section 3 (state machine) and 4 (data model).
-- Money is NUMERIC(12,2) INR.

CREATE TABLE receipts (
    id            BIGSERIAL    PRIMARY KEY,
    owner_id      BIGINT       NOT NULL REFERENCES users(id),
    file_name     VARCHAR(255) NOT NULL,
    content_type  VARCHAR(100) NOT NULL CHECK (content_type IN ('application/pdf', 'image/jpeg', 'image/png')),   -- BR-20
    size          BIGINT       NOT NULL CHECK (size > 0 AND size <= 5242880),                                        -- BR-20: max 5 MB
    sha256        VARCHAR(64)  NOT NULL,
    content       BYTEA        NOT NULL,
    uploaded_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
-- BR-7 duplicate receipt: same hash on an earlier line is a duplicate (rejected by the policy engine), so not unique here
CREATE INDEX receipts_sha256_idx ON receipts (sha256);
CREATE INDEX receipts_owner_idx ON receipts (owner_id);

CREATE TABLE claims (
    id              BIGSERIAL     PRIMARY KEY,
    code            VARCHAR(30)   NOT NULL UNIQUE,
    employee_id     BIGINT        NOT NULL REFERENCES users(id),
    title           VARCHAR(200)  NOT NULL,
    status          VARCHAR(30)   NOT NULL DEFAULT 'DRAFT',
    total_amount    NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK (total_amount >= 0),
    policy_outcome  VARCHAR(20),
    policy_result   JSONB,
    sample_audit    VARCHAR(10)   NOT NULL DEFAULT 'NONE',
    submitted_at    TIMESTAMPTZ,
    decided_at      TIMESTAMPTZ,
    paid_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version         BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT claims_status_ck CHECK (status IN (
        'DRAFT', 'PENDING_MANAGER', 'PENDING_AUDITOR', 'PENDING_HEAD', 'CLARIFICATION_REQUESTED',
        'APPROVED', 'REJECTED', 'AUTO_REJECTED', 'IN_PAYOUT', 'PAID')),
    CONSTRAINT claims_policy_outcome_ck CHECK (policy_outcome IS NULL OR policy_outcome IN ('AUTO_APPROVED', 'ROUTED', 'AUTO_REJECTED')),
    CONSTRAINT claims_sample_audit_ck CHECK (sample_audit IN ('NONE', 'PENDING', 'OK', 'EXCEPTION')),
    CONSTRAINT claims_submitted_needs_time CHECK (status = 'DRAFT' OR submitted_at IS NOT NULL),
    CONSTRAINT claims_paid_needs_time CHECK (status <> 'PAID' OR paid_at IS NOT NULL)
);
CREATE INDEX claims_employee_idx ON claims (employee_id);
CREATE INDEX claims_status_idx ON claims (status);
CREATE INDEX claims_submitted_at_idx ON claims (submitted_at);

CREATE TABLE claim_lines (
    id            BIGSERIAL     PRIMARY KEY,
    claim_id      BIGINT        NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
    line_no       INT           NOT NULL CHECK (line_no > 0),
    category      VARCHAR(10)   NOT NULL CHECK (category IN ('MEAL', 'HOTEL', 'CAB', 'TRAVEL', 'OTHER')),
    expense_date  DATE          NOT NULL,
    amount        NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    vendor        VARCHAR(150),
    description   VARCHAR(500),
    city          VARCHAR(80),
    nights        INT           CHECK (nights IS NULL OR nights > 0),
    receipt_id    BIGINT        REFERENCES receipts(id),
    violations    JSONB,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT claim_lines_no_uq UNIQUE (claim_id, line_no),
    CONSTRAINT claim_lines_hotel_nights CHECK (category <> 'HOTEL' OR nights IS NOT NULL)
);
CREATE INDEX claim_lines_claim_idx ON claim_lines (claim_id);
-- BR-6 duplicate line: same vendor (trimmed, case-insensitive) + amount + expense date
CREATE INDEX claim_lines_dup_idx ON claim_lines (lower(trim(vendor)), amount, expense_date);
CREATE INDEX claim_lines_category_idx ON claim_lines (category);

-- ADR-001: one row per level per round. A send-back (BR-16) restarts at level 1 in the next round.
-- decision NULL = pending. on_behalf_of_id = the original assignee when a delegate acted (BR-14).
CREATE TABLE approval_steps (
    id               BIGSERIAL    PRIMARY KEY,
    claim_id         BIGINT       NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
    round            INT          NOT NULL DEFAULT 1 CHECK (round > 0),
    level            VARCHAR(10)  NOT NULL CHECK (level IN ('MANAGER', 'AUDITOR', 'HEAD')),
    assignee_id      BIGINT       NOT NULL REFERENCES users(id),
    acted_by_id      BIGINT       REFERENCES users(id),
    on_behalf_of_id  BIGINT       REFERENCES users(id),
    decision         VARCHAR(10)  CHECK (decision IS NULL OR decision IN ('APPROVED', 'REJECTED', 'SENT_BACK')),
    comment          VARCHAR(1000),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at       TIMESTAMPTZ,
    CONSTRAINT approval_steps_round_level_uq UNIQUE (claim_id, round, level),
    CONSTRAINT approval_steps_decided_needs_time CHECK ((decision IS NULL) = (decided_at IS NULL)),
    CONSTRAINT approval_steps_decided_needs_actor CHECK (decision IS NULL OR acted_by_id IS NOT NULL)
);
CREATE INDEX approval_steps_pending_idx ON approval_steps (assignee_id) WHERE decision IS NULL;
CREATE INDEX approval_steps_claim_idx ON approval_steps (claim_id);

CREATE TABLE delegations (
    id            BIGSERIAL    PRIMARY KEY,
    delegator_id  BIGINT       NOT NULL REFERENCES users(id),
    delegate_id   BIGINT       NOT NULL REFERENCES users(id),
    from_date     DATE         NOT NULL,
    to_date       DATE         NOT NULL,
    reason        VARCHAR(300),
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT delegations_not_self CHECK (delegator_id <> delegate_id),
    CONSTRAINT delegations_dates CHECK (to_date >= from_date),                        -- BR-14
    CONSTRAINT delegations_max_30_days CHECK (to_date - from_date <= 30)              -- BR-14 / A-4
);
CREATE INDEX delegations_delegator_idx ON delegations (delegator_id) WHERE active;

CREATE TABLE claim_comments (
    id          BIGSERIAL    PRIMARY KEY,
    claim_id    BIGINT       NOT NULL REFERENCES claims(id) ON DELETE CASCADE,
    author_id   BIGINT       NOT NULL REFERENCES users(id),
    body        TEXT         NOT NULL CHECK (length(body) > 0),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX claim_comments_claim_idx ON claim_comments (claim_id);

CREATE TABLE payout_batches (
    id              BIGSERIAL     PRIMARY KEY,
    status          VARCHAR(15)   NOT NULL DEFAULT 'CREATED'
                    CHECK (status IN ('CREATED', 'SUBMITTED', 'PAID', 'SUBMIT_FAILED', 'CANCELLED')),
    created_by_id   BIGINT        NOT NULL REFERENCES users(id),
    item_count      INT           NOT NULL DEFAULT 0 CHECK (item_count >= 0),
    total           NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (total >= 0),
    value_date      DATE          NOT NULL,
    bank_reference  VARCHAR(80),
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    submitted_at    TIMESTAMPTZ,
    version         BIGINT        NOT NULL DEFAULT 0
);

CREATE TABLE payout_items (
    id          BIGSERIAL     PRIMARY KEY,
    batch_id    BIGINT        NOT NULL REFERENCES payout_batches(id) ON DELETE CASCADE,
    claim_id    BIGINT        NOT NULL REFERENCES claims(id),
    amount      NUMERIC(12,2) NOT NULL CHECK (amount > 0),
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- invariant (BR-18): a claim is in at most one batch, so it is never paid twice
    CONSTRAINT payout_items_claim_uq UNIQUE (claim_id)
);
CREATE INDEX payout_items_batch_idx ON payout_items (batch_id);
