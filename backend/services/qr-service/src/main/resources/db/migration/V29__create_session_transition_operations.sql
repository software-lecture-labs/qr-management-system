-- Branch-only V29 replacement, explicitly approved before shared deployment (ADR-QR-001).
-- Existing local QR history must be reset only with explicit owner permission; other DBs are untouched.
-- Stores QR's lifecycle command/receipt, never order/payment business state or guest credentials.
CREATE TABLE session_transition_operations (
    id UUID PRIMARY KEY,
    restaurant_id UUID NOT NULL,
    table_session_id UUID NOT NULL,
    request_json TEXT NOT NULL,
    request_digest VARCHAR(64) NOT NULL,
    stage VARCHAR(20) NOT NULL,
    result_json TEXT,
    requested_at TIMESTAMPTZ NOT NULL,
    last_attempt_at TIMESTAMPTZ,
    attempts INTEGER NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT fk_transition_session FOREIGN KEY (restaurant_id, table_session_id)
        REFERENCES table_sessions (restaurant_id, id),
    CONSTRAINT ck_transition_digest CHECK (request_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transition_request CHECK (jsonb_typeof(request_json::jsonb) = 'object'),
    CONSTRAINT ck_transition_stage CHECK (stage IN ('REQUESTED', 'APPLIED', 'ABORTING', 'COMPLETED', 'ABORTED')),
    CONSTRAINT ck_transition_receipt CHECK (
        (stage IN ('APPLIED', 'COMPLETED') AND result_json IS NOT NULL AND jsonb_typeof(result_json::jsonb) = 'object')
        OR (stage IN ('REQUESTED', 'ABORTING', 'ABORTED') AND result_json IS NULL)),
    CONSTRAINT ck_transition_time CHECK (isfinite(requested_at) AND (last_attempt_at IS NULL OR
        (isfinite(last_attempt_at) AND last_attempt_at >= requested_at))),
    CONSTRAINT ck_transition_revision CHECK (version >= 0 AND attempts >= 0)
);

CREATE UNIQUE INDEX uq_transition_active_session ON session_transition_operations (restaurant_id, table_session_id)
    WHERE stage IN ('REQUESTED', 'APPLIED', 'ABORTING');
CREATE INDEX ix_transition_pending ON session_transition_operations (stage, id);
