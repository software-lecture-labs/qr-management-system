-- Order Service ORDER-03 (range V41-V50): append-only status history of an order.
-- Two independent axes are recorded side by side: FULFILLMENT (RECEIVED...COMPLETED/CANCELLED) and PAYMENT.
-- Rows are never updated or deleted; a correction is a new row.

CREATE TABLE order_status_history (
    seq             bigint        GENERATED ALWAYS AS IDENTITY,
    id              uuid          NOT NULL,
    restaurant_id   uuid          NOT NULL,
    order_id        uuid          NOT NULL,
    state_axis      varchar(20)   NOT NULL,
    from_status     varchar(20),
    to_status       varchar(20)   NOT NULL,
    actor_type      varchar(20)   NOT NULL,
    actor_id        varchar(100),
    actor_role      varchar(30),
    source          varchar(40)   NOT NULL,
    source_event_id uuid,
    reason          varchar(300),
    occurred_at     timestamptz   NOT NULL,
    correlation_id  varchar(64),
    CONSTRAINT pk_order_status_history PRIMARY KEY (id),
    CONSTRAINT uq_order_status_history_seq UNIQUE (seq),
    CONSTRAINT fk_order_status_history_order FOREIGN KEY (restaurant_id, order_id)
        REFERENCES orders (restaurant_id, id),
    CONSTRAINT ck_order_status_history_axis CHECK (state_axis IN ('FULFILLMENT', 'PAYMENT')),
    CONSTRAINT ck_order_status_history_to CHECK (
        (state_axis = 'FULFILLMENT' AND to_status IN
            ('RECEIVED', 'PREPARING', 'READY', 'SERVED', 'COMPLETED', 'CANCELLED'))
        OR (state_axis = 'PAYMENT' AND to_status IN
            ('NOT_REQUESTED', 'PENDING', 'PAID', 'FAILED', 'CANCELLED'))),
    CONSTRAINT ck_order_status_history_from CHECK (from_status IS NULL OR (
        (state_axis = 'FULFILLMENT' AND from_status IN
            ('RECEIVED', 'PREPARING', 'READY', 'SERVED', 'COMPLETED', 'CANCELLED'))
        OR (state_axis = 'PAYMENT' AND from_status IN
            ('NOT_REQUESTED', 'PENDING', 'PAID', 'FAILED', 'CANCELLED')))),
    CONSTRAINT ck_order_status_history_actor CHECK (actor_type IN ('SYSTEM', 'GUEST', 'STAFF', 'SERVICE'))
);

-- Deterministic timeline of one order, tenant scoped.
CREATE INDEX ix_order_status_history_order ON order_status_history (restaurant_id, order_id, occurred_at, seq);

-- One change per external event and axis: the same event can never be recorded twice.
CREATE UNIQUE INDEX uq_order_status_history_event
    ON order_status_history (order_id, state_axis, source_event_id) WHERE source_event_id IS NOT NULL;

CREATE FUNCTION order_status_history_append_only() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'order status history is append-only' USING ERRCODE = 'check_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_order_status_history_append_only
    BEFORE UPDATE OR DELETE ON order_status_history
    FOR EACH ROW EXECUTE FUNCTION order_status_history_append_only();
