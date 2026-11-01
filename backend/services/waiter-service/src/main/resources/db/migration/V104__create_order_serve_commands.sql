-- WAITER-03: technical tracking of the "mark order as served" command that Waiter forwards to Order.
-- Order is the only authority of the served fact. This table never stores the delivery result (no servedAt,
-- no delivery status, no order version): it only remembers that a command was issued, by whom, with which
-- idempotency key, and how Order answered, so that an uncertain outcome (timeout, lost answer, restart) can be
-- reconciled with Order later. order_id and table_session_id are logical references, not foreign keys.
CREATE TABLE order_serve_commands (
    id                   uuid         NOT NULL,
    restaurant_id        uuid         NOT NULL,
    order_id             uuid         NOT NULL,
    table_session_id     uuid         NOT NULL,
    expected_version     bigint       NOT NULL,
    requested_by_user_id uuid         NOT NULL,
    idempotency_key      varchar(120) NOT NULL,
    request_digest       varchar(64)  NOT NULL,
    status               varchar(10)  NOT NULL,
    rejection_code       varchar(40),
    version              bigint       NOT NULL DEFAULT 0,
    created_at           timestamptz  NOT NULL,
    updated_at           timestamptz  NOT NULL,
    completed_at         timestamptz,
    CONSTRAINT pk_order_serve_commands PRIMARY KEY (id),
    CONSTRAINT uq_order_serve_commands_key UNIQUE (restaurant_id, requested_by_user_id, idempotency_key),
    CONSTRAINT ck_order_serve_commands_status CHECK (status IN ('PENDING', 'CONFIRMED', 'REJECTED', 'UNKNOWN')),
    CONSTRAINT ck_order_serve_commands_expected CHECK (expected_version >= 0),
    CONSTRAINT ck_order_serve_commands_version CHECK (version >= 0),
    -- A rejection always has its code and only a rejection has one; a command is completed exactly when Order
    -- gave a definite answer.
    CONSTRAINT ck_order_serve_commands_rejection CHECK ((status = 'REJECTED') = (rejection_code IS NOT NULL)),
    CONSTRAINT ck_order_serve_commands_completed CHECK ((status IN ('CONFIRMED', 'REJECTED')) = (completed_at IS NOT NULL))
);

CREATE INDEX ix_order_serve_commands_order ON order_serve_commands (restaurant_id, order_id, created_at);
CREATE INDEX ix_order_serve_commands_open ON order_serve_commands (restaurant_id, status) WHERE status IN ('PENDING', 'UNKNOWN');
