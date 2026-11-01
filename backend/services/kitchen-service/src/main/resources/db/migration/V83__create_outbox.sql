-- KITCHEN-02 task 2.4: durable local outbox (Kitchen range V81-V90, docs/decisions/migrations.md; docs/database.md §13).
-- An event is written in the SAME transaction as the business change that causes it. Delivery to other services is
-- tracked per destination; nothing here talks to another service or database.

CREATE TABLE outbox_events (
    id                uuid         NOT NULL,   -- the eventId, stable across redeliveries
    restaurant_id     uuid         NOT NULL,
    aggregate_type    varchar(40)  NOT NULL,
    aggregate_id      uuid         NOT NULL,   -- logical REF: the Order service's order id
    aggregate_version bigint       NOT NULL,   -- Kitchen order version after the change
    event_type        varchar(60)  NOT NULL,
    schema_version    integer      NOT NULL,
    correlation_id    varchar(64),
    causation_id      uuid,                    -- the command (Idempotency-Key) that caused the change
    payload           jsonb        NOT NULL,
    occurred_at       timestamptz  NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT pk_outbox_events PRIMARY KEY (id),
    CONSTRAINT uq_outbox_events_tenant_id UNIQUE (restaurant_id, id),
    -- One event of a kind per aggregate: a repeated or racing command can never produce a second KitchenOrderReady.
    CONSTRAINT uq_outbox_events_aggregate_event UNIQUE (restaurant_id, aggregate_type, aggregate_id, event_type),
    CONSTRAINT ck_outbox_events_schema CHECK (schema_version >= 1),
    CONSTRAINT ck_outbox_events_aggregate_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_outbox_events_payload CHECK (jsonb_typeof(payload) = 'object')
);

CREATE TABLE outbox_deliveries (
    id               uuid         NOT NULL,
    restaurant_id    uuid         NOT NULL,
    event_id         uuid         NOT NULL,
    destination      varchar(40)  NOT NULL,
    status           varchar(20)  NOT NULL,
    attempt_count    integer      NOT NULL DEFAULT 0,
    next_attempt_at  timestamptz,
    last_attempt_at  timestamptz,
    delivered_at     timestamptz,
    last_error_code  varchar(80),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    version          bigint       NOT NULL DEFAULT 0,
    CONSTRAINT pk_outbox_deliveries PRIMARY KEY (id),
    CONSTRAINT fk_outbox_deliveries_event FOREIGN KEY (restaurant_id, event_id)
        REFERENCES outbox_events (restaurant_id, id),
    CONSTRAINT uq_outbox_deliveries_event_destination UNIQUE (restaurant_id, event_id, destination),
    CONSTRAINT ck_outbox_deliveries_status CHECK (status IN ('PENDING', 'DELIVERED', 'FAILED')),
    CONSTRAINT ck_outbox_deliveries_attempts CHECK (attempt_count >= 0),
    CONSTRAINT ck_outbox_deliveries_delivered CHECK (
        (status = 'DELIVERED') = (delivered_at IS NOT NULL)
    ),
    CONSTRAINT ck_outbox_deliveries_pending CHECK (status <> 'PENDING' OR next_attempt_at IS NOT NULL)
);

CREATE INDEX ix_outbox_deliveries_due ON outbox_deliveries (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_outbox_deliveries_event ON outbox_deliveries (restaurant_id, event_id);
