-- WAITER-04: technical record of the table commands that Waiter forwards to QR ("close the main table session",
-- "mark the table as cleaned") and the terminal state of calls that end with a closed table session.
--
-- QR is the only authority of tables, main and guest sessions, closure and cleaning. This table never stores
-- the table or session state itself: it remembers that a command was issued, by whom, under which idempotency
-- key and how QR answered, so that a command whose answer was lost (timeout, restart) can be found again by its
-- id and reconciled with QR. table_id and table_session_id are logical references, not foreign keys.
CREATE TABLE table_commands (
    id                   uuid         NOT NULL,
    restaurant_id        uuid         NOT NULL,
    command_type         varchar(10)  NOT NULL,
    table_id             uuid         NOT NULL,
    table_session_id     uuid,
    expected_version     bigint       NOT NULL,
    requested_by_user_id uuid         NOT NULL,
    idempotency_key      varchar(120) NOT NULL,
    request_digest       varchar(64)  NOT NULL,
    reason_text          varchar(300),
    status               varchar(12)  NOT NULL,
    rejection_code       varchar(40),
    rejection_detail     varchar(300),
    qr_closed_at         timestamptz,
    ended_guest_sessions integer,
    cancelled_call_count integer,
    table_version_after  bigint,
    ready_for_new_customer boolean,
    -- ADR-QR-001 K13: QR confirmed the close (CLOSED) but still names RECONCILIATION_REQUIRED in its blockers because
    -- Order found a scope drift after QR applied the closure. The closure stays a definite fact; the flag only keeps
    -- that warning with the completed command. Waiter never reopens, resends or settles anything because of it.
    reconciliation_required boolean     NOT NULL DEFAULT false,
    version              bigint       NOT NULL DEFAULT 0,
    created_at           timestamptz  NOT NULL,
    updated_at           timestamptz  NOT NULL,
    completed_at         timestamptz,
    CONSTRAINT pk_table_commands PRIMARY KEY (id),
    CONSTRAINT uq_table_commands_tenant UNIQUE (restaurant_id, id),
    CONSTRAINT uq_table_commands_key UNIQUE (restaurant_id, requested_by_user_id, idempotency_key),
    CONSTRAINT ck_table_commands_type CHECK (command_type IN ('CLOSE', 'CLEAN')),
    CONSTRAINT ck_table_commands_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'REJECTED', 'UNKNOWN')),
    -- A close targets one main session; cleaning targets the table and carries no session.
    CONSTRAINT ck_table_commands_session CHECK ((command_type = 'CLOSE') = (table_session_id IS NOT NULL)),
    CONSTRAINT ck_table_commands_reason CHECK (reason_text IS NULL OR (command_type = 'CLOSE' AND char_length(reason_text) BETWEEN 1 AND 300)),
    CONSTRAINT ck_table_commands_expected CHECK (expected_version >= 0),
    CONSTRAINT ck_table_commands_version CHECK (version >= 0),
    -- A rejection always has its code and only a rejection has one; a command is completed exactly when QR gave a
    -- definite answer (completed or rejected).
    CONSTRAINT ck_table_commands_rejection CHECK ((status = 'REJECTED') = (rejection_code IS NOT NULL)),
    CONSTRAINT ck_table_commands_completed CHECK ((status IN ('COMPLETED', 'REJECTED')) = (completed_at IS NOT NULL)),
    CONSTRAINT ck_table_commands_counts CHECK ((ended_guest_sessions IS NULL OR ended_guest_sessions >= 0)
        AND (cancelled_call_count IS NULL OR cancelled_call_count >= 0)),
    -- A completed cleaning always carries the table version QR reported and whether QR showed the table as ready.
    CONSTRAINT ck_table_commands_clean_done CHECK (status <> 'COMPLETED' OR command_type <> 'CLEAN'
        OR (table_version_after IS NOT NULL AND ready_for_new_customer IS NOT NULL)),
    -- A completed close always carries what QR confirmed and how many calls were ended with the session.
    CONSTRAINT ck_table_commands_close_done CHECK (status <> 'COMPLETED' OR command_type <> 'CLOSE'
        OR (qr_closed_at IS NOT NULL AND ended_guest_sessions IS NOT NULL AND cancelled_call_count IS NOT NULL)),
    -- Only a completed close can carry the K13 warning.
    CONSTRAINT ck_table_commands_reconciliation CHECK (
        NOT reconciliation_required OR (command_type = 'CLOSE' AND status = 'COMPLETED'))
);

-- At most one unanswered close per main session and one unanswered cleaning per table: a second command with
-- another key is refused instead of racing the first one. Exactly one close can complete for a session.
CREATE UNIQUE INDEX uq_table_commands_open_close ON table_commands (restaurant_id, table_session_id)
    WHERE command_type = 'CLOSE' AND status IN ('PENDING', 'IN_PROGRESS', 'UNKNOWN');
CREATE UNIQUE INDEX uq_table_commands_open_clean ON table_commands (restaurant_id, table_id)
    WHERE command_type = 'CLEAN' AND status IN ('PENDING', 'IN_PROGRESS', 'UNKNOWN');
CREATE UNIQUE INDEX uq_table_commands_closed_session ON table_commands (restaurant_id, table_session_id)
    WHERE command_type = 'CLOSE' AND status = 'COMPLETED';

CREATE INDEX ix_table_commands_table ON table_commands (restaurant_id, table_id, created_at);
CREATE INDEX ix_table_commands_open ON table_commands (updated_at) WHERE status IN ('PENDING', 'IN_PROGRESS', 'UNKNOWN');

-- A call that was still unresolved when its table session closed is not "resolved" (nobody did the work): it
-- ends in CANCELLED with the reason and the closing command, and stays in the history. The CHECKs are NOT VALID
-- like in V102: every new or updated row is enforced; no row can be CANCELLED before this migration.
ALTER TABLE service_requests
    ADD COLUMN closure_reason varchar(30),
    ADD COLUMN cancelled_at timestamptz,
    ADD COLUMN cancelled_by_command_id uuid;

ALTER TABLE service_requests
    ADD CONSTRAINT fk_service_requests_closing_command FOREIGN KEY (restaurant_id, cancelled_by_command_id)
        REFERENCES table_commands (restaurant_id, id),
    ADD CONSTRAINT ck_service_requests_closure_reason CHECK (closure_reason IS NULL OR closure_reason IN ('TABLE_SESSION_CLOSED')) NOT VALID,
    ADD CONSTRAINT ck_service_requests_cancelled CHECK (
        (status = 'CANCELLED') = (closure_reason IS NOT NULL AND cancelled_at IS NOT NULL AND cancelled_by_command_id IS NOT NULL)) NOT VALID;

CREATE INDEX ix_service_requests_session_status ON service_requests (restaurant_id, table_session_id, status);

-- The audit trail and the Notification queue learn the fourth business change of a call.
ALTER TABLE service_call_events DROP CONSTRAINT ck_service_call_events_type;
ALTER TABLE service_call_events
    ADD CONSTRAINT ck_service_call_events_type CHECK (event_type IN ('CREATED', 'CLAIMED', 'RESOLVED', 'CANCELLED'));
