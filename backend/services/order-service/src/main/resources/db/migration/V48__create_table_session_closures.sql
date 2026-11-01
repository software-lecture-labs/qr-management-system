-- Order Service ADR-QR-001 (K8, K9; range V41-V50): durable record of a table-session lifecycle operation the QR Service
-- asked for, so that a permit the Order Service gave is remembered across restarts and lost responses, and so that no
-- order is accepted while a permit that must keep orders out is held.
--
-- The Order Service owns orders and decides, from them, whether a table session may be closed or may expire idle. The
-- QR Service owns the table and its sessions and performs the change; it names one operation (operation_id) per
-- command, so asking again with the same id never prepares or applies a second time. restaurant_id, table_session_id
-- and the guest ids are logical REFs to the QR Service (no cross-DB FK).
--
-- purpose / effect (the QR Service's own vocabulary):
--   SETTLED / SETTLED_CLOSE   every real order is finished and settled: the whole session may be closed
--   IDLE    / IDLE_MAIN       no order protects the session: main and all guests may expire idle
--   IDLE    / IDLE_GUESTS     only the guests named in idle_guest_ids may expire; orders protect the rest
-- command_digest is the QR Service's digest of the exact command; the same operation with another digest is a conflict.
--
-- Only an authorised operation is stored:
--   PREPARED   the permit is held; checkout answers 409 TABLE_SESSION_CLOSING for everybody (SETTLED_CLOSE, IDLE_MAIN)
--              or for the named guests (IDLE_GUESTS); the facts it was decided on are pinned by scope_fingerprint
--   COMPLETED  the QR Service confirmed it applied the change; final. The optional receipt_* columns hold the receipt
--              of the local result it reported
--   RELEASED   the QR Service confirmed it did NOT apply the change; final, orders are accepted again
-- A PREPARED operation never expires by itself: a timeout does not prove that the change failed (K9). At most one
-- operation per table session is PREPARED, or COMPLETED with an effect that ends the session, at any time, enforced
-- here whatever the application code does (a COMPLETED IDLE_GUESTS operation does not end the session).
-- Checkout and preparation are ordered by a database advisory lock per table session (see the service code); this
-- table holds the durable outcome of that ordering.

CREATE TABLE table_session_closures (
    operation_id             uuid         NOT NULL,
    restaurant_id            uuid         NOT NULL,
    table_session_id         uuid         NOT NULL,
    purpose                  varchar(10)  NOT NULL,
    effect                   varchar(14)  NOT NULL,
    command_digest           varchar(64)  NOT NULL,
    idle_guest_ids           text         NOT NULL DEFAULT '',
    status                   varchar(10)  NOT NULL,
    order_count              integer      NOT NULL,
    scope_fingerprint        varchar(64)  NOT NULL,
    prepared_at              timestamptz  NOT NULL,
    completed_at             timestamptz,
    released_at              timestamptz,
    receipt_session_version  bigint,
    receipt_table_version    bigint,
    receipt_completed_at     timestamptz,
    receipt_expired_guests   integer,
    CONSTRAINT pk_table_session_closures PRIMARY KEY (operation_id),
    CONSTRAINT ck_table_session_closures_status CHECK (status IN ('PREPARED', 'COMPLETED', 'RELEASED')),
    CONSTRAINT ck_table_session_closures_purpose CHECK (
        (purpose = 'SETTLED' AND effect = 'SETTLED_CLOSE' AND idle_guest_ids = '')
        OR (purpose = 'IDLE' AND effect = 'IDLE_MAIN' AND idle_guest_ids = '')
        OR (purpose = 'IDLE' AND effect = 'IDLE_GUESTS' AND idle_guest_ids <> '')),
    CONSTRAINT ck_table_session_closures_count CHECK (order_count >= 0 AND (purpose <> 'SETTLED' OR order_count > 0)),
    CONSTRAINT ck_table_session_closures_fingerprint CHECK (scope_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_table_session_closures_digest CHECK (command_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_table_session_closures_times CHECK (
        (status = 'PREPARED' AND completed_at IS NULL AND released_at IS NULL)
        OR (status = 'COMPLETED' AND completed_at IS NOT NULL AND released_at IS NULL)
        OR (status = 'RELEASED' AND released_at IS NOT NULL AND completed_at IS NULL)),
    -- a receipt is all or nothing, and only a COMPLETED operation can carry one
    CONSTRAINT ck_table_session_closures_receipt CHECK (
        (receipt_session_version IS NULL AND receipt_table_version IS NULL AND receipt_completed_at IS NULL
            AND receipt_expired_guests IS NULL)
        OR (status = 'COMPLETED' AND receipt_session_version >= 0 AND receipt_table_version >= 0
            AND receipt_completed_at IS NOT NULL AND receipt_expired_guests >= 0))
);

-- One live operation per table session: a second PREPARED one, or one next to a COMPLETED operation that ended the
-- session, is impossible.
CREATE UNIQUE INDEX uq_table_session_closures_live
    ON table_session_closures (restaurant_id, table_session_id)
    WHERE status = 'PREPARED' OR (status = 'COMPLETED' AND effect <> 'IDLE_GUESTS');

CREATE INDEX ix_table_session_closures_session ON table_session_closures (restaurant_id, table_session_id);

-- An operation is never deleted and its identity and facts never change. PREPARED may move once, to COMPLETED
-- (optionally with a receipt) or RELEASED; after that it is final.
CREATE FUNCTION table_session_closures_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'table session closures are never deleted' USING ERRCODE = 'check_violation';
    END IF;
    IF NEW.operation_id IS DISTINCT FROM OLD.operation_id OR NEW.restaurant_id IS DISTINCT FROM OLD.restaurant_id
        OR NEW.table_session_id IS DISTINCT FROM OLD.table_session_id
        OR NEW.purpose IS DISTINCT FROM OLD.purpose OR NEW.effect IS DISTINCT FROM OLD.effect
        OR NEW.command_digest IS DISTINCT FROM OLD.command_digest
        OR NEW.idle_guest_ids IS DISTINCT FROM OLD.idle_guest_ids
        OR NEW.order_count IS DISTINCT FROM OLD.order_count
        OR NEW.scope_fingerprint IS DISTINCT FROM OLD.scope_fingerprint
        OR NEW.prepared_at IS DISTINCT FROM OLD.prepared_at THEN
        RAISE EXCEPTION 'table session closure % is immutable', OLD.operation_id USING ERRCODE = 'check_violation';
    END IF;
    IF OLD.status <> 'PREPARED' AND (NEW.status IS DISTINCT FROM OLD.status
        OR NEW.completed_at IS DISTINCT FROM OLD.completed_at OR NEW.released_at IS DISTINCT FROM OLD.released_at
        OR NEW.receipt_session_version IS DISTINCT FROM OLD.receipt_session_version
        OR NEW.receipt_table_version IS DISTINCT FROM OLD.receipt_table_version
        OR NEW.receipt_completed_at IS DISTINCT FROM OLD.receipt_completed_at
        OR NEW.receipt_expired_guests IS DISTINCT FROM OLD.receipt_expired_guests) THEN
        RAISE EXCEPTION 'table session closure % is already %, its outcome is final', OLD.operation_id, OLD.status
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_table_session_closures_guard
    BEFORE UPDATE OR DELETE ON table_session_closures
    FOR EACH ROW EXECUTE FUNCTION table_session_closures_guard();
