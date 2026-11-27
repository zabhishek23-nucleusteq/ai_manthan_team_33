-- Audit trail + idempotency base (T-018). FR-13, NFR-AUDIT, NFR-IDEM, failure-modes.md.

-- Append-only record of every action (invariant 6). Rows are written by AuditService in the same
-- transaction as the change they describe. actor_id NULL = system (seed, scheduled job).
CREATE TABLE audit_events (
    id               BIGSERIAL    PRIMARY KEY,
    at               TIMESTAMPTZ  NOT NULL DEFAULT now(),
    actor_id         BIGINT       REFERENCES users(id),
    on_behalf_of_id  BIGINT       REFERENCES users(id),
    action           VARCHAR(60)  NOT NULL,
    entity_type      VARCHAR(40)  NOT NULL,
    entity_id        VARCHAR(64)  NOT NULL,
    before           JSONB,
    after            JSONB
);
CREATE INDEX audit_events_entity_idx ON audit_events (entity_type, entity_id, at);
CREATE INDEX audit_events_actor_idx ON audit_events (actor_id, at);
CREATE INDEX audit_events_at_idx ON audit_events (at);

-- History cannot be edited or removed, not even by the application's own DB user (failure-modes "audit trail").
CREATE FUNCTION audit_events_append_only() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only: % is not allowed', TG_OP
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$;

CREATE TRIGGER audit_events_no_update_delete
    BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION audit_events_append_only();

CREATE TRIGGER audit_events_no_truncate
    BEFORE TRUNCATE ON audit_events
    FOR EACH STATEMENT EXECUTE FUNCTION audit_events_append_only();

-- One row per (user, Idempotency-Key). request_hash = SHA-256 of method + path + body: same key with another
-- hash -> 422 IDEMPOTENCY_KEY_REUSED. IN_PROGRESS while the first request runs; COMPLETED stores the response
-- that every replay returns. 5xx responses are not stored (the row is removed so the client can retry).
CREATE TABLE idempotency_keys (
    id                     BIGSERIAL    PRIMARY KEY,
    user_id                BIGINT       NOT NULL,
    idem_key               VARCHAR(64)  NOT NULL,
    request_hash           CHAR(64)     NOT NULL,
    status                 VARCHAR(12)  NOT NULL DEFAULT 'IN_PROGRESS' CHECK (status IN ('IN_PROGRESS', 'COMPLETED')),
    response_status        INT,
    response_content_type  VARCHAR(100),
    response_body          TEXT,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at           TIMESTAMPTZ,
    CONSTRAINT idempotency_keys_user_key_uq UNIQUE (user_id, idem_key),
    CONSTRAINT idempotency_keys_completed_has_response CHECK (status <> 'COMPLETED' OR response_status IS NOT NULL)
);
