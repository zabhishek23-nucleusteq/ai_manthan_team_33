-- ADR-002: policy rules as configuration, not code. Each rule code maps to one evaluator class in the
-- policy package; values and action come from the row. FINANCE_HEAD edits rows via PUT /api/policy-rules/{code}.
-- Rows without an action are settings (approval chain thresholds, auto-approve max, sample audit rate).

CREATE TABLE policy_rules (
    code           VARCHAR(40)   PRIMARY KEY,
    description    VARCHAR(200)  NOT NULL,
    category       VARCHAR(10)   CHECK (category IS NULL OR category IN ('MEAL', 'HOTEL', 'CAB', 'TRAVEL', 'OTHER')),
    limit_amount   NUMERIC(14,2) CHECK (limit_amount IS NULL OR limit_amount >= 0),
    unit           VARCHAR(10)   CHECK (unit IS NULL OR unit IN ('PER_DAY', 'PER_NIGHT', 'PER_TRIP', 'PER_LINE', 'PER_CLAIM')),
    params         JSONB,
    action         VARCHAR(10)   CHECK (action IS NULL OR action IN ('ROUTE', 'REJECT')),
    enabled        BOOLEAN       NOT NULL DEFAULT TRUE,
    updated_by_id  BIGINT        REFERENCES users(id),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    version        BIGINT        NOT NULL DEFAULT 0
);

INSERT INTO policy_rules (code, description, category, limit_amount, unit, params, action) VALUES
    -- BR-1..BR-8 (docs/requirements.md)
    ('MEAL_DAILY_LIMIT',    'BR-1 Meals up to 1,500 INR per employee per day',                   'MEAL',  1500.00, 'PER_DAY',   NULL, 'ROUTE'),
    ('HOTEL_NIGHTLY_LIMIT', 'BR-2/BR-3 Hotel up to 6,000 INR per night in tier-1 cities, 4,000 elsewhere', 'HOTEL', 6000.00, 'PER_NIGHT',
        '{"otherCitiesLimit": 4000.00, "tier1Cities": ["Mumbai", "Delhi", "New Delhi", "Bengaluru", "Bangalore", "Chennai", "Kolkata", "Hyderabad", "Pune", "Ahmedabad"]}',
        'ROUTE'),
    ('CAB_TRIP_LIMIT',      'BR-4 Local cab up to 2,000 INR per trip (one line = one trip)',     'CAB',   2000.00, 'PER_TRIP',  NULL, 'ROUTE'),
    ('RECEIPT_REQUIRED',    'BR-5 Receipt mandatory above 500 INR',                              NULL,     500.00, 'PER_LINE',  NULL, 'REJECT'),
    ('DUPLICATE_LINE',      'BR-6 Same vendor + amount + date as an earlier line',               NULL,       NULL, 'PER_LINE',  NULL, 'REJECT'),
    ('DUPLICATE_RECEIPT',   'BR-7 Same receipt file (SHA-256) as an earlier line',               NULL,       NULL, 'PER_LINE',  NULL, 'REJECT'),
    ('WEEKEND_SPEND',       'BR-8 Weekend spend flagged for manager attention',                  NULL,       NULL, 'PER_LINE',  NULL, 'ROUTE'),
    -- settings (BR-9, BR-11, BR-12, BR-13)
    ('AUTO_APPROVE_MAX',    'BR-9 Clean claims up to this total are auto-approved (INR)',        NULL,    5000.00, 'PER_CLAIM', NULL, NULL),
    ('CHAIN_AUDITOR_ABOVE', 'BR-11 Finance auditor approval needed above this total (INR)',      NULL,   25000.00, 'PER_CLAIM', NULL, NULL),
    ('CHAIN_HEAD_ABOVE',    'BR-12 Finance head approval needed above this total (INR)',         NULL,  100000.00, 'PER_CLAIM', NULL, NULL),
    ('SAMPLE_AUDIT_RATE',   'BR-13 Share of auto-approved claims flagged for sample audit (0-1)', NULL,      0.10, NULL,        NULL, NULL);
